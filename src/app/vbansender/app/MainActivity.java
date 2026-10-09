package app.vbansender.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
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
                    boolean enabled = !prefs.getBoolean("enabled", false);
                    AppConfig.save(prefs.edit().putBoolean("enabled", enabled)
                            .putString("controller_state", enabled ? "STARTING" : "STOPPING")
                            .remove("last_error"));
                    startForegroundService(new Intent(MainActivity.this, VbanService.class));
                } catch (Exception error) {
                    AppConfig.save(prefs.edit().putString("last_error", error.toString()));
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
        note.setText("Netflix uses standard output due to capture issues.");
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
        boolean enabled = prefs.getBoolean("enabled", false);
        toggle.setText(enabled ? "ON" : "OFF");
        toggle.setBackground(buttonBackground(enabled ? Color.rgb(35, 110, 71)
                : Color.rgb(51, 64, 78)));
        toggle.setEnabled(enabled || AppConfig.configured(this));
        String error = prefs.getString("last_error", "");
        String state = prefs.getString("controller_state", "OFF");
        if (!error.isEmpty()) {
            status.setText("Error: " + error + "\nTurn OFF, then ON to retry.");
        } else if ("STARTING".equals(state) || "STOPPING".equals(state)) {
            status.setText("STARTING".equals(state) ? "Starting…" : "Stopping…");
        } else if (!AppConfig.configured(this)) {
            status.setText("Open Settings to configure the receiver and local ADB.");
        } else {
            status.setText(enabled ? "Audio via VBAN"
                    : "Audio via standard output");
        }
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
        prefs.registerOnSharedPreferenceChangeListener(changes);
        if (AppConfig.needsReconcile(this)) {
            try {
                startForegroundService(new Intent(this, VbanService.class));
            } catch (Exception error) {
                AppConfig.save(prefs.edit().putString("last_error", error.toString()));
            }
        }
        render();
    }

    @Override
    public void onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(changes);
        super.onPause();
    }
}
