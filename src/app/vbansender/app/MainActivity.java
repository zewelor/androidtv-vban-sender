package app.vbansender.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Daily TV screen: one remembered audio-routing choice and visible errors. */
public final class MainActivity extends Activity {
    private Button toggle;
    private TextView status;
    private SharedPreferences prefs;
    private boolean renderedEnabled;
    private boolean renderedRetryOff;
    private boolean resumed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable countdown = new Runnable() {
        @Override public void run() { render(); }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener changes =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences store, String key) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    render();
                }
            });
        }
    };

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = AppConfig.prefs(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(100, 48, 100, 48);
        layout.setBackgroundColor(Color.rgb(17, 23, 31));
        TextView title = new TextView(this);
        title.setText("VBAN");
        title.setTextSize(38);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        layout.addView(title);
        toggle = new Button(this);
        toggle.setTextSize(52);
        toggle.setTextColor(Color.WHITE);
        toggle.setBackgroundTintList(null);
        LinearLayout.LayoutParams big = new LinearLayout.LayoutParams(760, 280);
        big.topMargin = 35;
        big.bottomMargin = 16;
        layout.addView(toggle, big);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    boolean enabled = !renderedEnabled && !renderedRetryOff;
                    VbanService.setEnabled(MainActivity.this, enabled);
                } catch (Exception error) {
                    AppConfig.save(prefs.edit().putString("last_error", error.toString())
                            .putString("last_error_summary", AppConfig.errorSummary(error)));
                }
                render();
            }
        });
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(23);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams details = new LinearLayout.LayoutParams(-1, 160);
        details.topMargin = 18;
        layout.addView(status, details);
        Button settings = new Button(this);
        settings.setText("Settings");
        settings.setTextColor(Color.WHITE);
        settings.setBackgroundTintList(null);
        settings.setBackground(buttonBackground(Color.rgb(51, 64, 78)));
        layout.addView(settings, new LinearLayout.LayoutParams(360, 85));
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        TextView note = new TextView(this);
        note.setText("Netflix and Home use standard output.");
        note.setTextSize(18);
        note.setTextColor(Color.rgb(170, 180, 190));
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteLayout = new LinearLayout.LayoutParams(-1, -2);
        noteLayout.topMargin = 32;
        layout.addView(note, noteLayout);
        setContentView(layout);
        toggle.requestFocus();
    }

    private void render() {
        main.removeCallbacks(countdown);
        boolean enabled = prefs.getBoolean("enabled", false);
        boolean retryOff = retryOff();
        renderedEnabled = enabled;
        renderedRetryOff = retryOff;
        String error = prefs.getString("last_error", "");
        String state = prefs.getString("controller_state", "OFF");
        String label;
        String message;
        int color = Color.rgb(51, 64, 78);
        boolean stopping = "STOPPING".equals(state) || !enabled && AppConfig.needsReconcile(this);
        if (!error.isEmpty()) {
            label = retryOff ? "RETRY OFF" : "ERROR";
            color = Color.rgb(153, 44, 44);
            message = retryOff ? "Turning VBAN off did not finish.\nPress OK to retry. Details in Settings."
                    : prefs.getString("last_error_summary", "Audio routing needs attention.")
                        + (enabled ? "\nPress OK to turn VBAN off, then try again."
                                : "\nPress OK to try again. Details in Settings.");
        } else if (stopping) {
            label = "STOPPING…";
            color = Color.rgb(153, 101, 20);
            message = "Restoring standard audio. Please wait.";
        } else if (!enabled) {
            label = "OFF";
            message = AppConfig.configured(this) ? "Audio via standard output.\nPress OK to turn VBAN on."
                    : "Open Settings to configure the receiver and local ADB.";
        } else if ("WAITING_NETWORK".equals(state)) {
            label = "WAITING";
            color = Color.rgb(153, 101, 20);
            long seconds = AppConfig.networkRetrySeconds(prefs, SystemClock.uptimeMillis());
            message = (seconds == 0 ? "Retrying connection…" : "Network unavailable. Retrying in " + seconds + " s.")
                    + "\nPress OK to turn VBAN off.";
            if (resumed) main.postDelayed(countdown, 1000);
        } else if ("ON".equals(state)) {
            label = "ON";
            color = Color.rgb(35, 110, 71);
            message = "Automatic audio routing ready.\nPress OK to turn VBAN off.";
        } else {
            label = "STARTING…";
            color = Color.rgb(153, 101, 20);
            message = "Preparing audio routing.\nPress OK to cancel.";
        }
        toggle.setText(label);
        toggle.setBackground(buttonBackground(color));
        toggle.setEnabled(!error.isEmpty() || retryOff || !stopping && (enabled || AppConfig.configured(this)));
        status.setText(message);
    }

    private boolean retryOff() {
        return !prefs.getBoolean("enabled", false) && !prefs.getString("last_error", "").isEmpty()
                && AppConfig.needsReconcile(this);
    }

    private StateListDrawable buttonBackground(int color) {
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_focused}, buttonShape(color, true));
        background.addState(new int[] {android.R.attr.state_pressed}, buttonShape(color, true));
        background.addState(new int[] {}, buttonShape(color, false));
        return background;
    }

    private GradientDrawable buttonShape(int color, boolean focused) {
        float density = getResources().getDisplayMetrics().density;
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(12 * density);
        if (focused) {
            shape.setStroke(Math.round(3 * density), Color.WHITE);
        }
        return shape;
    }

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        prefs.registerOnSharedPreferenceChangeListener(changes);
        if (AppConfig.needsReconcile(this)) {
            try {
                startForegroundService(new Intent(this, VbanService.class));
            } catch (Exception error) {
                AppConfig.save(prefs.edit().putString("last_error", error.toString())
                            .putString("last_error_summary", AppConfig.errorSummary(error)));
            }
        }
        render();
    }

    @Override
    public void onPause() {
        resumed = false;
        main.removeCallbacks(countdown);
        prefs.unregisterOnSharedPreferenceChangeListener(changes);
        super.onPause();
    }
}
