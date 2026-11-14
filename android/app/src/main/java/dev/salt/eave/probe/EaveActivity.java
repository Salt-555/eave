package dev.salt.eave.probe;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

/**
 * One screen: is it listening, what it can currently hear, and what it suspects.
 *
 * The ambient line is not decoration. The detectors are relative, and a relative
 * detector with no visible floor is indistinguishable from one that is broken.
 * Seeing "17-21 kHz median -118 dBFS" is what tells you the room is quiet enough
 * for the alarm to mean something.
 */
public final class EaveActivity extends Activity {

    private static final int REQ_MIC = 1;

    private TextView headline;
    private TextView ambientView;
    private TextView findingsView;
    private Button toggle;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            redraw();
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        headline = new TextView(this);
        headline.setTextSize(16f);
        headline.setTypeface(null, Typeface.BOLD);
        box.addView(headline);

        ambientView = new TextView(this);
        ambientView.setTextSize(12f);
        box.addView(ambientView);

        findingsView = new TextView(this);
        findingsView.setTextSize(13f);
        box.addView(findingsView);

        toggle = new Button(this);
        toggle.setOnClickListener(v -> {
            if (EaveService.isRunning()) {
                stopService(new Intent(this, EaveService.class));
            } else if (granted()) {
                start();
            } else {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            }
        });
        box.addView(toggle);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_MIC + 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresh);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQ_MIC && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            start();
        }
    }

    private void start() {
        Intent in = new Intent(this, EaveService.class);
        in.setAction(EaveService.ACTION_START);
        startForegroundService(in);
    }

    private boolean granted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void redraw() {
        boolean on = EaveService.isRunning();
        toggle.setText(on ? "Stop listening" : "Start listening");
        if (!on && EaveService.startedAt() != 0) {
            toggle.setText("Start again");
        }
        headline.setText(on ? "Listening. " + EaveService.captureInfo()
                : "Not listening.");

        String amb = EaveService.ambient();
        ArrayList<String> found = EaveService.findings();
        StringBuilder sb = new StringBuilder();
        sb.append("\nIn the room right now:\n").append(amb);
        sb.append("\nSuspicions this session: ").append(found.size()).append("\n");
        if (found.isEmpty()) {
            sb.append("Nothing that looks like a beacon or a periodic mark.\n"
                    + "Silence here means no near-ultrasonic tone and no repeating "
                    + "in-band mark. It does not mean nothing is measuring this "
                    + "television: plain-audio acoustic fingerprinting emits nothing "
                    + "to hear.");
        } else {
            for (String line : found) {
                sb.append("- ").append(line).append('\n');
            }
        }
        sb.append(String.format(Locale.US,
                "\n\nNo storage, no network permission. The microphone is only open "
                        + "while this is running, and Android shows that in the status bar."));
        findingsView.setText(sb);
    }
}
