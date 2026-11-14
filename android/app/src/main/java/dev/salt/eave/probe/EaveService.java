package dev.salt.eave.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Holds the microphone and runs the detectors continuously.
 *
 * Capture source preference is UNPROCESSED, then MIC, then DEFAULT, then
 * VOICE_RECOGNITION. That order is measured, not assumed: on this handset
 * UNPROCESSED sat 15-35 dB quieter in the 17-21 kHz band than anything else
 * (docs/DEVICE-P0.md), and it is the only source that skips noise suppression
 * and automatic gain, the two processings most likely to remove or move a
 * narrow near-ultrasonic tone before the detector ever sees it.
 *
 * There is no INTERNET permission in the manifest. Nothing here can send what
 * it hears anywhere, which is the only trust argument a counter-surveillance
 * tool is allowed to make about itself.
 */
public final class EaveService extends Service {

    public static final String ACTION_START = "dev.salt.eave.probe.START";
    public static final String ACTION_STOP = "dev.salt.eave.probe.STOP";

    private static final String TAG = "EAVE";
    private static final String CHANNEL = "eave-listening";

    private static final ArrayList<String> FINDINGS = new ArrayList<>();
    private static volatile String ambient = "starting";
    private static volatile String captureInfo = "not started";
    private static volatile boolean running = false;
    private static volatile long startedAt = 0;
    private static long lastReportMs = 0;
    private static String lastKind = "";
    private static int repeats = 0;

    /** Where a finished line goes: logcat, when a capture is live. The
     *  service installs that writer; with no service, as in the JVM tests,
     *  nothing happens and no android.util.Log class is ever touched. */
    static volatile java.util.function.Consumer<String> AUDIT = line -> {
    };

    /** The detectors hold this instead of naming this class. */
    public static final Report REPORT = EaveService::report;

    /** Called through REPORT. Repeats of the same kind are folded into one
     *  line for two minutes rather than scrolling the screen off. */
    static void report(String kind, String detail, double confidence) {
        long now = System.currentTimeMillis();
        String line;
        synchronized (FINDINGS) {
            if (kind.equals(lastKind) && now - lastReportMs < 120_000L) {
                repeats++;
                if (!FINDINGS.isEmpty()) {
                    FINDINGS.remove(0);
                }
                line = kind + ": " + detail + "  [repeat " + repeats
                        + " inside 2 min, replaced rather than stacked]";
            } else {
                repeats = 0;
                line = kind + ": " + detail + String.format("  [confidence %.1f]", confidence);
            }
            final String forLog = line;
            AUDIT.accept(forLog);
            lastKind = kind;
            lastReportMs = now;
            FINDINGS.add(0, line + "  " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
            if (FINDINGS.size() > 40) {
                FINDINGS.remove(FINDINGS.size() - 1);
            }
        }
    }

    static ArrayList<String> findings() {
        synchronized (FINDINGS) {
            return new ArrayList<>(FINDINGS);
        }
    }

    static String ambient() {
        return ambient;
    }

    static String captureInfo() {
        return captureInfo;
    }

    /** Only for the JVM detector tests, which run with no device and no
     *  process lifetime to reset state with. */
    static void resetForTests() {
        synchronized (FINDINGS) {
            FINDINGS.clear();
        }
        lastKind = "";
        lastReportMs = 0;
        ambient = "starting";
    }

    static boolean isRunning() {
        return running;
    }

    static long startedAt() {
        return startedAt;
    }

    private static String minutesRunning() {
        return String.format(Locale.US, "%.1f", (System.currentTimeMillis() - startedAt) / 60000.0);
    }

    private Thread worker;
    private volatile boolean stop;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // The microphone permission must be granted before startForeground
        // for a microphone-typed service (the FGS docs are explicit about the
        // order). The Activity guards this; a bare `am start` does not, and
        // on Android 14+ the un-guarded call is a crash instead of a message.
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "EAVE_NO_PERMISSION RECORD_AUDIO not granted; refusing start");
            return START_NOT_STICKY;
        }
        channel();
        startForeground(1, notification("Eave is listening", "Analysing the room, not storing it"));
        // isAlive, not null: if the capture loop died (stalled reads, a
        // framework exception) the field is non-null but nothing is listening,
        // and a null-check would turn "Start again" into a no-op forever.
        if (worker == null || !worker.isAlive()) {
            stop = false;
            running = true;
            startedAt = System.currentTimeMillis();
            worker = new Thread(this::listen, "eave-capture");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stop = true;
        running = false;
        if (worker != null) {
            try {
                worker.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
        super.onDestroy();
    }

    /** createNotificationChannel with an existing id updates it, so calling
     *  this every start is correct and needs no existence probe. */
    private void channel() {
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Listening",
                        NotificationManager.IMPORTANCE_LOW));
    }

    private Notification notification(String title, String body) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(title)
                .setContentText(body)
                .setOngoing(true);
        Intent open = new Intent(this, EaveActivity.class);
        b.setContentIntent(android.app.PendingIntent.getActivity(
                this, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE));
        return b.build();
    }

    private void listen() {
        AUDIT = line -> Log.i(TAG, "SUSPECT " + line);
        ToneDetector tone = new ToneDetector();
        MarkDetector mark = new MarkDetector();
        // the detectors only know the Report interface; this is where logcat
        // enters, and it is why the same classes run unmodified on a JVM
        tone.report = REPORT;
        mark.report = REPORT;
        AudioRecord rec = null;
        String chosen = null;
        int[] tries = {
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
        };
        int rate = 48000;
        int min = AudioRecord.getMinBufferSize(rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        for (int src : tries) {
            try {
                AudioRecord candidate = new AudioRecord(src, rate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(min * 4, rate * 2));
                if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                    rec = candidate;
                    chosen = sourceName(src);
                    break;
                }
                candidate.release();
            } catch (Exception e) {
                Log.i(TAG, "SOURCE_UNAVAILABLE " + sourceName(src) + " " + e);
            }
        }
        if (rec == null) {
            captureInfo = "no AudioRecord source would initialise";
            Log.e(TAG, "EAVE_NO_SOURCE");
            running = false;
            stopSelf();        // same rule as the finally below
            return;
        }

        int block = 4800;                      // 100 ms
        short[] buf = new short[block];
        captureInfo = chosen + " @" + rate + " Hz mono";
        Log.i(TAG, "CAPTURE_OPEN " + captureInfo + " minBuffer=" + min);
        long lastAmbient = 0;
        long ambientTick = 0;
        int dropped = 0;
        try {
            rec.startRecording();
            while (!stop) {
                int n = rec.read(buf, 0, block);
                if (n <= 0) {
                    if (++dropped > 50) {
                        Log.e(TAG, "EAVE_READ_STALLED n=" + n);
                        break;
                    }
                    continue;
                }
                dropped = 0;
                tone.feed(buf, n);
                mark.feed(buf, n);
                long now = System.currentTimeMillis();
                if (now - lastAmbient > 5000) {
                    lastAmbient = now;
                    ambientTick += 5000;
                    if (ambientTick >= 30000) {
                        // The quiet-case reading is the result of a run in which
                        // nothing is found, which is exactly the result a screen
                        // does not preserve: the display sleeps, the view tree
                        // goes with it, and an hour of listening leaves nothing
                        // to show for itself. Thirty seconds is enough to see a
                        // room's floor drift without drowning in it.
                        ambientTick = 0;
                        Log.i(TAG, "AMBIENT " + minutesRunning() + " min | " + ambient);
                    }
                    AudioDeviceInfo routed = rec.getRoutedDevice();
                    AudioRecordingConfiguration cfg = rec.getActiveRecordingConfiguration();
                    // isClientSilenced() is API 29 per the reference; calling it
                    // unguarded on minSdk 26 is a NoSuchMethodError on Android 8-10,
                    // thrown from the one loop that must not die.
                    boolean silenced = Build.VERSION.SDK_INT >= 29
                            && cfg != null && cfg.isClientSilenced();
                    ambient = String.format(Locale.US,
                            "17-21 kHz median %.1f dBFS, best tone %.0f Hz at %.1f dBFS "
                                    + "(%.1f dB over band). Mark z now %.1f (%s), session max "
                                    + "%.1f (%s). %s",
                            tone.medianDbfs, tone.bestFreqHz, tone.bestDbfs,
                            tone.bestContrastDb, mark.lastZ, mark.lastZLabel,
                            mark.sessionMaxZ, mark.sessionMaxLabel,
                            routed == null ? "route unknown"
                                    : "input " + routed.getType()
                                    + (silenced ? ", SILENCED BY SYSTEM" : ""));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "EAVE_FAIL " + e, e);
        } finally {
            try {
                rec.stop();
            } catch (Exception ignore) {
                // never started, or already stopped
            }
            rec.release();
            running = false;
            captureInfo = "stopped";
            AUDIT = line -> {
            };
            Log.i(TAG, "CAPTURE_CLOSED");
            // The listener died; a foreground service whose only job is
            // listening must not keep a notification that claims otherwise.
            // stopSelf runs onDestroy on the main thread, never here.
            stopSelf();
        }
    }

    private static String sourceName(int src) {
        switch (src) {
            case MediaRecorder.AudioSource.UNPROCESSED:
                return "UNPROCESSED";
            case MediaRecorder.AudioSource.MIC:
                return "MIC";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION:
                return "VOICE_RECOGNITION";
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION:
                return "VOICE_COMMUNICATION";
            default:
                return "source" + src;
        }
    }
}
