package app.vbansender.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;
import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.vbansender.Heartbeat;

/** Phase 0 self-ADB proof, audio-free screen for local ADB and a finite boot proof. */
public final class ProofActivity extends Activity {
    private TextView status;
    private TextView engineSnapshot;
    private Button test;
    private Button boot;
    private static final String TAG = "VBAN-PROOF";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refreshEngineSnapshot = new Runnable() {
        @Override
        public void run() {
            updateEngineSnapshot();
            main.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(64, 40, 64, 40);
        TextView title = new TextView(this);
        title.setText("VBAN — developer startup test");
        title.setTextSize(26);
        layout.addView(title);
        status = new TextView(this);
        status.setTextSize(19);
        status.setText("Check local ADB and a separate test process. Audio output is unchanged.");
        layout.addView(status);
        engineSnapshot = new TextView(this);
        engineSnapshot.setTextSize(16);
        layout.addView(engineSnapshot);
        test = new Button(this);
        updateTestButton();
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                test.setEnabled(false);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        check();
                    }
                }, "self-adb-proof").start();
            }
        });
        layout.addView(test);
        boot = new Button(this);
        boot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                SharedPreferences prefs = getSharedPreferences("proof", MODE_PRIVATE);
                boolean enable = !prefs.getBoolean("boot_enabled", false);
                if (!prefs.edit().putBoolean("boot_enabled", enable).commit()) {
                    show("FAIL: could not save the boot test setting");
                }
                updateBootButton();
            }
        });
        layout.addView(boot);
        updateBootButton();
        setContentView(layout);
        test.requestFocus();
    }

    private void show(final String message) {
        Log.i(TAG, message);
        getSharedPreferences("proof", MODE_PRIVATE).edit().putString("status", message).apply();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                status.setText(message);
                updateBootButton();
            }
        });
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        updateTestButton();
    }

    private void updateTestButton() {
        int seconds = getIntent().getIntExtra("proof_seconds", Heartbeat.DEFAULT_SECONDS);
        test.setText("Check local ADB — heartbeat " + seconds + " s");
    }

    @Override
    public void onResume() {
        super.onResume();
        String previous = getSharedPreferences("proof", MODE_PRIVATE).getString("status", "");
        if (!previous.isEmpty()) {
            status.setText(previous);
        }
        updateEngineSnapshot();
        main.removeCallbacks(refreshEngineSnapshot);
        main.postDelayed(refreshEngineSnapshot, 1000);
        updateBootButton();
    }

    @Override
    public void onPause() {
        main.removeCallbacks(refreshEngineSnapshot);
        super.onPause();
    }

    private void updateEngineSnapshot() {
        String raw = getSharedPreferences("vban", MODE_PRIVATE).getString("engine_snapshot", "");
        try {
            EngineState snapshot = EngineState.parse(raw);
            engineSnapshot.setText(formatEngineSnapshot(snapshot));
        } catch (IllegalStateException invalid) {
            engineSnapshot.setText("Engine snapshot unavailable: invalid status.");
        }
    }

    static String formatEngineSnapshot(EngineState snapshot) {
        if (snapshot == null) {
            return "Engine snapshot: no status observed yet.";
        }
        StringBuilder text = new StringBuilder("Engine snapshot: ").append(snapshot.state);
        if ("IDLE".equals(snapshot.state)) {
            text.append(" (idle; neutral)");
        }
        if (!snapshot.metricsAvailable) {
            return text.append("\nCapture telemetry unavailable (legacy status).").toString();
        }
        text.append("\nFrames read: ").append(snapshot.framesRead)
                .append("   Packets sent: ").append(snapshot.sent)
                .append("   Dropped: ").append(snapshot.dropped)
                .append("\nStatus observed: ").append(age(snapshot.observedNanos));
        if (snapshot.framesRead == 0) {
            text.append("\nNo audio frames observed yet (neutral).");
        } else {
            long lastProgress = Math.max(snapshot.lastReadNanos, snapshot.lastSendNanos);
            text.append("\nLast audio progress: ").append(age(lastProgress));
        }
        return text.toString();
    }

    private static String age(long timestampNanos) {
        if (timestampNanos <= 0) return "not available";
        long elapsed = System.nanoTime() - timestampNanos;
        if (elapsed < 0) return "not available";
        return (elapsed / 1000000000L) + " s ago";
    }

    private void updateBootButton() {
        SharedPreferences prefs = getSharedPreferences("proof", MODE_PRIVATE);
        boot.setText("Boot test: " + (prefs.getBoolean("boot_enabled", false) ? "ON" : "OFF"));
        boot.setEnabled(prefs.getBoolean("authorized", false));
    }

    private void check() {
        try {
            String result = SelfAdbProof.run(this, new SelfAdbProof.Progress() {
                @Override
                public void report(String message) {
                    show(message);
                }
            }, 90000, getIntent().getIntExtra("proof_seconds", Heartbeat.DEFAULT_SECONDS));
            getSharedPreferences("proof", MODE_PRIVATE).edit().putBoolean("authorized", true).apply();
            show(result);
        } catch (Exception error) {
            Log.e(TAG, "Self-ADB proof failed", error);
            show("FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    test.setEnabled(true);
                }
            });
        }
    }
}
