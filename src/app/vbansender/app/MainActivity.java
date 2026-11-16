package app.vbansender.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
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
import android.widget.ProgressBar;
import android.widget.TextView;

/** Daily TV screen: one remembered audio-routing choice and visible errors. */
public final class MainActivity extends Activity {
    private Button toggle;
    private TextView status;
    private TextView stateTitle;
    private TextView indicator;
    private ProgressBar progress;
    private Button settings;
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
        title.setText("VBAN Streamer");
        title.setTextSize(32);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        layout.addView(title);
        LinearLayout stateRow = new LinearLayout(this);
        stateRow.setOrientation(LinearLayout.HORIZONTAL);
        stateRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stateLayout = new LinearLayout.LayoutParams(-1, 110);
        stateLayout.topMargin = 20;
        layout.addView(stateRow, stateLayout);
        indicator = new TextView(this);
        indicator.setText("●");
        indicator.setTextSize(32);
        indicator.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dotLayout = new LinearLayout.LayoutParams(50, -2);
        dotLayout.rightMargin = 18;
        stateRow.addView(indicator, dotLayout);
        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(Color.rgb(235, 177, 48)));
        LinearLayout.LayoutParams progressLayout = new LinearLayout.LayoutParams(48, 48);
        progressLayout.rightMargin = 20;
        stateRow.addView(progress, progressLayout);
        stateTitle = new TextView(this);
        stateTitle.setTextSize(34);
        stateTitle.setTypeface(null, Typeface.BOLD);
        stateTitle.setTextColor(Color.WHITE);
        stateRow.addView(stateTitle);
        toggle = new Button(this);
        toggle.setTextSize(40);
        toggle.setTextColor(Color.WHITE);
        toggle.setBackgroundTintList(null);
        LinearLayout.LayoutParams big = new LinearLayout.LayoutParams(760, 160);
        big.topMargin = 16;
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
        LinearLayout.LayoutParams details = new LinearLayout.LayoutParams(-1, 190);
        details.topMargin = 18;
        layout.addView(status, details);
        settings = new Button(this);
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
        String heading;
        boolean busy = false;
        int color = Color.rgb(150, 162, 174);
        boolean stopping = "STOPPING".equals(state) || !enabled && AppConfig.needsReconcile(this);
        if (!error.isEmpty()) {
            heading = "ERROR";
            label = retryOff ? "RETRY OFF" : enabled ? "TURN OFF" : "TURN ON";
            color = Color.rgb(235, 77, 77);
            message = retryOff ? "Turning VBAN off did not finish.\nRetry turning off. Details in Settings."
                    : prefs.getString("last_error_summary", "Audio routing needs attention.")
                        + (enabled ? "\nTurn VBAN off, then on to try again. Details in Settings."
                                : "\nDetails in Settings.");
        } else if (stopping) {
            heading = "STOPPING…";
            busy = true;
            label = "PLEASE WAIT";
            color = Color.rgb(235, 177, 48);
            message = "Restoring standard audio.";
        } else if (!enabled) {
            heading = "OFF";
            label = "TURN ON";
            message = AppConfig.configured(this) ? "Audio via standard output."
                    : "Open Settings to configure the receiver and local ADB.";
        } else if ("WAITING_NETWORK".equals(state)) {
            heading = "WAITING FOR NETWORK";
            label = "TURN OFF";
            color = Color.rgb(235, 177, 48);
            long seconds = AppConfig.networkRetrySeconds(prefs, SystemClock.uptimeMillis());
            message = (seconds == 0 ? "Retrying connection…" : "Network unavailable. Retrying in " + seconds + " s.")
                    + "\nRetry resumes automatically.";
            if (resumed) main.postDelayed(countdown, 1000);
        } else if ("ON".equals(state)) {
            heading = "ON";
            label = "TURN OFF";
            color = Color.rgb(42, 200, 102);
            message = "Automatic audio routing ready.";
        } else {
            heading = "STARTING…";
            busy = true;
            label = "CANCEL";
            color = Color.rgb(235, 177, 48);
            message = "Preparing audio routing.";
        }
        stateTitle.setText(heading);
        indicator.setTextColor(color);
        indicator.setVisibility(busy ? View.GONE : View.VISIBLE);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        boolean actionable = !error.isEmpty() || retryOff || !stopping && (enabled || AppConfig.configured(this));
        boolean hadFocus = toggle.hasFocus();
        toggle.setText(label);
        toggle.setTextColor(actionable ? Color.WHITE : Color.rgb(133, 142, 151));
        toggle.setBackground(buttonBackground(actionable ? Color.rgb(51, 64, 78) : Color.rgb(38, 47, 59)));
        toggle.setEnabled(actionable);
        if (!actionable && hadFocus) settings.requestFocus();
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
