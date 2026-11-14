package dev.salt.eave.probe;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Eave P0 probe. Its only job is to answer two questions about this specific
 * handset, because the answer decides which detector the project is allowed to
 * have:
 *
 *   1. Does the microphone path carry any energy at 17-21 kHz at all, or does the
 *      HAL low-pass it away before an application can see it? The Android
 *      compatibility definition permits a large droop between 7 kHz and 22 kHz on
 *      a device that still advertises UNPROCESSED support, so "it is in the
 *      datasheet" is not an answer. Only a measurement is.
 *   2. Which AudioSource actually reaches that band unprocessed. Android applies
 *      noise suppression, AGC and echo cancellation per source, and the
 *      near-ultrasonic content a beacon lives in is exactly what those stages are
 *      designed to discard as hiss.
 *
 * Deliberately no DSP here beyond per-frequency levels: analysis stays in Python
 * against pulled WAVs, where a threshold change costs seconds instead of a Gradle
 * cycle. Everything above 24 kHz is unreachable by construction, since the input
 * path tops out at 48 kHz sampling (Android 17, Pixel 10 Pro: only Bluetooth A2DP
 * output advertises 96 kHz), so the sweep stops just under Nyquist.
 */
public class ProbeActivity extends Activity {

    private static final String TAG = "EAVE";

    /** Sweep points, Hz. The last three sit within 2 kHz of Nyquist on purpose:
     *  where the curve falls off tells us the real ceiling, not the nominal one. */
    private static final int[] PROBE_HZ = {
            500, 1000, 2000, 3000, 4000, 5000, 6000, 8000, 10000, 12000,
            14000, 15000, 16000, 17000, 17500, 18000, 19000, 19500, 20000,
            21000, 22000, 23000, 23500
    };

    private static final int[][] SOURCES = {
            {MediaRecorder.AudioSource.DEFAULT, 0},
            {MediaRecorder.AudioSource.MIC, 1},
            {MediaRecorder.AudioSource.CAMCORDER, 5},
            {MediaRecorder.AudioSource.VOICE_RECOGNITION, 6},
            {MediaRecorder.AudioSource.VOICE_COMMUNICATION, 7},
            {MediaRecorder.AudioSource.UNPROCESSED, 9},
    };

    private TextView status;
    private PowerManager.WakeLock wake;
    private volatile boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(12f);
        status.setText("Idle. RECORD_AUDIO grant is "
                + (granted() ? "held" : "MISSING (pm grant dev.salt.eave.probe android.permission.RECORD_AUDIO)")
                + ".\n\nSweep points: " + PROBE_HZ.length + " tones, "
                + PROBE_HZ[0] + "-" + PROBE_HZ[PROBE_HZ.length - 1] + " Hz.");
        box.addView(status);

        addRow(box, "UNPROCESSED 10 s", () -> capture("unprocessed", 10, 48000));
        addRow(box, "MIC 10 s", () -> capture("mic", 10, 48000));
        addRow(box, "VOICE_RECOGNITION 10 s", () -> capture("voice_recognition", 10, 48000));
        addRow(box, "all sources, 3 s each", () -> capture("ladder", 3, 48000));
        addRow(box, "unprocessed @ 96 kHz", () -> capture("unprocessed", 3, 96000));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        setContentView(scroll);

        PowerManager pm = getSystemService(PowerManager.class);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eave:probe");
        wake.setReferenceCounted(false);

        // adb-driven runs: am start -n dev.salt.eave.probe/.ProbeActivity
        //                  --es source ladder --ei secs 3 --ez run true
        String source = getIntent().getStringExtra("source");
        if (getIntent().getBooleanExtra("run", false)) {
            int secs = getIntent().getIntExtra("secs", 10);
            int rate = getIntent().getIntExtra("rate", 48000);
            capture(source == null ? "unprocessed" : source, secs, rate);
        }
    }

    private void addRow(LinearLayout box, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        box.addView(b);
    }

    private boolean granted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------------ capture

    private void capture(String sourceName, int secs, int rate) {
        if (busy) {
            say("A capture is already running.");
            return;
        }
        if (!granted()) {
            say("RECORD_AUDIO not granted. On a debug build: pm grant "
                    + getPackageName() + " android.permission.RECORD_AUDIO");
            Log.e(TAG, "EAVE_PERM_DENIED");
            return;
        }
        busy = true;
        wake.acquire();
        new Thread(() -> {
            try {
                if (sourceName.equalsIgnoreCase("ladder")) {
                    for (int[] s : SOURCES) {
                        runOne(sourceNameFor(s[0]), s[0], secs, rate);
                    }
                } else {
                    runOne(sourceName, sourceFor(sourceName), secs, rate);
                }
            } catch (Exception e) {
                Log.e(TAG, "EAVE_FAIL " + e.getClass().getSimpleName() + " " + e.getMessage());
                runOnUiThread(() -> status.append("\nFAILED: " + e));
            } finally {
                busy = false;
                wake.release();
                Log.i(TAG, "EAVE_DONE");
                if (getIntent().getBooleanExtra("run", false)) {
                    finish();
                }
            }
        }, "eave-probe").start();
    }

    private void runOne(String label, int source, int secs, int rate) {
        int min = AudioRecord.getMinBufferSize(rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            log("EAVE_REJECT source=" + label + " rate=" + rate
                    + " reason=getMinBufferSize=" + min);
            return;
        }
        int n = Math.max(min * 4, rate);
        AudioRecord rec = null;
        try {
            rec = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, n * 2);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                log("EAVE_REJECT source=" + label + " rate=" + rate
                        + " reason=not initialised");
                return;
            }
            rec.startRecording();
            short[] buf = new short[n];
            List<short[]> chunks = new ArrayList<>();
            long want = (long) rate * secs;
            long got = 0;
            long t0 = System.nanoTime();
            while (got < want) {
                int read = rec.read(buf, 0, n);
                if (read <= 0) {
                    log("EAVE_READ_ERROR source=" + label + " read=" + read);
                    break;
                }
                short[] piece = new short[read];
                System.arraycopy(buf, 0, piece, 0, read);
                chunks.add(piece);
                got += read;
            }
            double wall = (System.nanoTime() - t0) / 1e9;

            AudioRecord.ActiveRecordingConfiguration cfg =
                    rec.getActiveRecordingConfiguration();
            AudioDeviceInfo routed = rec.getRoutedDevice();
            StringBuilder sb = new StringBuilder();
            sb.append("EAVE source=").append(label)
              .append(" requestedRate=").append(rate)
              .append(" actualRate=").append(rec.getSampleRate())
              .append(" seconds=").append(fmt((double) got / rate))
              .append(" wall=").append(fmt(wall))
              .append(" silenced=").append(cfg != null && cfg.isClientSilenced())
              .append(" routedType=").append(routed == null ? -1 : routed.getType());

            int total = (int) got;
            double[] x = new double[total];
            int p = 0;
            for (short[] c : chunks) {
                for (short v : c) {
                    x[p++] = v / 32768.0;
                }
            }
            sb.append(" rms=").append(fmt(db(x, 0, total)));
            sb.append(" Hz:");
            for (int hz : PROBE_HZ) {
                sb.append(' ').append(hz).append("=").append(fmt(goertzelDbfs(x, hz, rate)));
            }
            log(sb.toString());

            File dir = getExternalFilesDir(null);
            File wav = new File(dir, label + "_" + rate + "_" + secs + "s.wav");
            writeWav(wav, chunks, rate);
            log("EAVE_WROTE " + wav.getAbsolutePath() + " " + wav.length() + " bytes");
        } catch (Exception e) {
            log("EAVE_FAIL source=" + label + " " + e);
        } finally {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (IllegalStateException ignored) {
                }
                rec.release();
            }
        }
    }

    // ------------------------------------------------------------------ metrics

    /** Goertzel magnitude of one frequency, as an amplitude against 0 dBFS. */
    private static double goertzelDbfs(double[] x, double freq, int fs) {
        double w = 2 * Math.PI * freq / fs;
        double coeff = 2 * Math.cos(w);
        double s0 = 0, s1 = 0, s2 = 0;
        for (double v : x) {
            s0 = v + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double re = s1 - s2 * Math.cos(w);
        double im = s2 * Math.sin(w);
        double amp = 2 * Math.sqrt(re * re + im * im) / x.length;
        return 20 * Math.log10(amp + 1e-12);
    }

    private static double db(double[] x, int from, int to) {
        double acc = 0;
        for (int i = from; i < to; i++) {
            acc += x[i] * x[i];
        }
        return 10 * Math.log10(acc / Math.max(1, to - from) + 1e-12);
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }

    // -------------------------------------------------------------------- plumbing

    private static int sourceFor(String name) {
        switch (name.toLowerCase()) {
            case "default":            return MediaRecorder.AudioSource.DEFAULT;
            case "mic":                return MediaRecorder.AudioSource.MIC;
            case "camcorder":          return MediaRecorder.AudioSource.CAMCORDER;
            case "voice_recognition":  return MediaRecorder.AudioSource.VOICE_RECOGNITION;
            case "voice_communication":return MediaRecorder.AudioSource.VOICE_COMMUNICATION;
            case "unprocessed":        return MediaRecorder.AudioSource.UNPROCESSED;
            default:                   return MediaRecorder.AudioSource.UNPROCESSED;
        }
    }

    private static String sourceNameFor(int source) {
        switch (source) {
            case MediaRecorder.AudioSource.DEFAULT:             return "default";
            case MediaRecorder.AudioSource.MIC:                 return "mic";
            case MediaRecorder.AudioSource.CAMCORDER:           return "camcorder";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION:   return "voice_recognition";
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION: return "voice_communication";
            case MediaRecorder.AudioSource.UNPROCESSED:         return "unprocessed";
            default:                                            return "source_" + source;
        }
    }

    private static void writeWav(File file, List<short[]> chunks, int rate) throws IOException {
        int samples = 0;
        for (short[] c : chunks) {
            samples += c.length;
        }
        ByteBuffer bb = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes());
        bb.putInt(36 + samples * 2);
        bb.put("WAVE".getBytes());
        bb.put("fmt ".getBytes());
        bb.putInt(16);
        bb.putShort((short) 1);                       // PCM
        bb.putShort((short) 1);                       // mono
        bb.putInt(rate);
        bb.putInt(rate * 2);
        bb.putShort((short) 2);
        bb.putShort((short) 16);
        bb.put("data".getBytes());
        bb.putInt(samples * 2);
        for (short[] c : chunks) {
            for (short v : c) {
                bb.putShort(v);
            }
        }
        try (OutputStream os = new FileOutputStream(file)) {
            os.write(bb.array());
        }
    }

    private void log(String line) {
        Log.i(TAG, line);
        runOnUiThread(() -> status.append("\n" + line));
    }

    private void say(String s) {
        Log.i(TAG, "EAVE_NOTE " + s);
        runOnUiThread(() -> status.setText(s));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (wake != null && wake.isHeld()) {
            wake.release();
        }
    }
}
