package app.vbansender.app;

import android.widget.Button;
import android.widget.TextView;
import java.lang.reflect.Field;

/** Exercises the real Activity's rendered controls through small Android adapters. */
public final class MainActivityRetryHarness {
    public static void main(String[] args) throws Exception {
        MemoryPrefs prefs = new MemoryPrefs();
        android.content.Context.prefs = prefs;
        configure(prefs);
        String scenario = args[0];

        if (scenario.equals("failed_off_cleanup") || scenario.equals("failed_off_unconfigured")
                || scenario.equals("retry_displayed_after_cleanup")) {
            AppConfig.save(prefs.edit().putBoolean("enabled", false)
                    .putBoolean("cleanup_pending", true).putString("active_run", "old-run")
                    .putString("last_error", "IOException: cleanup timeout"));
            if (scenario.equals("failed_off_unconfigured")) {
                AppConfig.save(prefs.edit().putString("receiver", "").putBoolean("authorized", false));
            }
        } else if (scenario.equals("network_wait")) {
            AppConfig.save(prefs.edit().putBoolean("enabled", true)
                    .putString("controller_state", "WAITING_NETWORK").putString("network_retry_seconds", "300")
                    .putString("network_retry_at_uptime_ms", Long.toString(android.os.SystemClock.now + 300000)));
        } else if (scenario.equals("failed_stop_requested_on")) {
            AppConfig.save(prefs.edit().putBoolean("enabled", true).putBoolean("cleanup_pending", true)
                    .putString("controller_state", "STOPPING").putString("last_error", "IOException: cleanup failed"));
        } else if (scenario.equals("state_transitions")) {
            AppConfig.save(prefs.edit().putBoolean("enabled", true).putString("controller_state", "STARTING"));
        } else if (scenario.equals("ordinary_off")) {
            AppConfig.save(prefs.edit().putBoolean("enabled", false));
        } else if (scenario.equals("failed_start")) {
            AppConfig.save(prefs.edit().putBoolean("enabled", true)
                    .putString("last_error", "IOException: start failed")
                    .putString("last_error_summary", AppConfig.errorSummary(new java.io.IOException("ADB unavailable"))));
        } else if (scenario.equals("off_error_without_pending")) {
            AppConfig.save(prefs.edit().putBoolean("enabled", false)
                    .putString("last_error", "IOException: status read failed"));
        } else {
            throw new AssertionError("Unknown scenario: " + scenario);
        }

        MainActivity activity = new MainActivity();
        activity.onCreate(null);
        activity.onResume();
        Button button = field(activity, "toggle", Button.class);
        TextView status = field(activity, "status", TextView.class);

        if (scenario.equals("failed_off_cleanup") || scenario.equals("failed_off_unconfigured")
                || scenario.equals("retry_displayed_after_cleanup")) {
            require("RETRY OFF".contentEquals(button.getText()),
                    "Failed OFF cleanup must label the control RETRY OFF; got " + button.getText());
            require(button.isEnabled(), "OFF cleanup retry must not require valid ON configuration");
            require(status.getText().toString().contains("Turning VBAN off did not finish"),
                    "Failed OFF cleanup must explain that OFF is the retry action: " + status.getText());
            if (scenario.equals("retry_displayed_after_cleanup")) {
                // Worker persistence can finish before its queued UI refresh runs.
                AppConfig.save(prefs.edit().remove("cleanup_pending").remove("active_run")
                        .remove("pending_off_token").remove("last_error"));
            }
            button.performClick();
            require(Boolean.FALSE.equals(VbanService.lastEnabled),
                    "Retry control requested ON after failed OFF cleanup");
            require(!prefs.getBoolean("enabled", true)
                    && !prefs.getString("pending_off_token", "").isEmpty(),
                    "OFF retry did not preserve the saved OFF choice and create a fresh obligation");
        } else if (scenario.equals("network_wait")) {
            require("TURN OFF".contentEquals(button.getText()) && status.getText().toString().contains("Retrying in")
                    && status.getText().toString().contains("300 s"), "Waiting state or retry interval missing");
            activity.onPause();
            android.os.SystemClock.now += 1500;
            activity.onResume();
            require(status.getText().toString().contains("299 s"), "Reopening UI reset the countdown: " + status.getText());
            field(activity, "settings", Button.class).requestFocus();
            android.os.SystemClock.now += 1000;
            android.os.Handler.fireDelay(1000);
            Runnable tick;
            while ((tick = android.os.Handler.queue.poll()) != null) tick.run();
            require(status.getText().toString().contains("298 s"), "Visible countdown did not advance");
            require(field(activity, "settings", Button.class).hasFocus(), "Countdown stole remote focus");
            activity.onPause();
            require(android.os.Handler.delayed.isEmpty(), "Paused activity retained countdown ticks");
            android.os.SystemClock.now += 400000;
            activity.onResume();
            require(status.getText().toString().contains("Retrying connection…"), "Expired countdown did not show progress");
            button.performClick();
            require(Boolean.FALSE.equals(VbanService.lastEnabled), "Waiting network did not allow OFF");
        } else if (scenario.equals("failed_stop_requested_on")) {
            require("TURN OFF".contentEquals(button.getText()) && button.isEnabled(), "Cleanup failure blocked OFF recovery");
            button.performClick();
            require(Boolean.FALSE.equals(VbanService.lastEnabled), "Failed cleanup did not request a fresh OFF");
        } else if (scenario.equals("state_transitions")) {
            String[] states = {"STARTING", "ON", "WAITING_NETWORK", "STOPPING"};
            String[] labels = {"CANCEL", "TURN OFF", "TURN OFF", "PLEASE WAIT"};
            String[] headings = {"STARTING…", "ON", "WAITING FOR NETWORK", "STOPPING…"};

            for (int i = 0; i < states.length; i++) {
                AppConfig.save(prefs.edit().putString("controller_state", states[i]));
                activity.onPause(); activity.onResume();
                require(labels[i].contentEquals(button.getText()), "Misleading label for " + states[i] + ": " + button.getText());
                require(headings[i].contentEquals(field(activity, "stateTitle", TextView.class).getText()),
                        "State must appear above the action: " + states[i]);
                android.widget.ProgressBar progress = field(activity, "progress", android.widget.ProgressBar.class);
                require((progress.getVisibility() == android.view.View.VISIBLE)
                        == (i == 0 || i == 3), "Wrong progress visibility: " + states[i]);
                if (i < 3) require(((android.graphics.drawable.StateListDrawable) button.background).color
                        == android.graphics.Color.rgb(51,64,78), "Action color must be neutral");
                require(button.isEnabled() == !"STOPPING".equals(states[i]), "Wrong cancellation availability: " + states[i]);
                if (i == 3) require(field(activity, "settings", Button.class).hasFocus(),
                        "Disabled action left the remote without visible focus");
            }
            AppConfig.save(prefs.edit().putString("controller_state", "STARTING"));
            activity.onPause(); activity.onResume();
            button.performClick();
            require(Boolean.FALSE.equals(VbanService.lastEnabled), "Starting could not be cancelled");
        } else if (scenario.equals("ordinary_off")) {
            require("TURN ON".contentEquals(button.getText())
                    && "OFF".contentEquals(field(activity, "stateTitle", TextView.class).getText()), "OFF state and TURN ON action must be separate");
            button.performClick();
            require(Boolean.TRUE.equals(VbanService.lastEnabled), "Ordinary OFF did not request ON");
            require(prefs.getBoolean("enabled", false), "ON choice was not persisted");
        } else if (scenario.equals("failed_start")) {
            require("TURN OFF".contentEquals(button.getText()) && !status.getText().toString().contains("IOException")
                    && status.getText().toString().contains("Android audio service")
                    && "ERROR".contentEquals(field(activity, "stateTitle", TextView.class).getText()),
                    "Failed start must show ERROR separately from TURN OFF: " + status.getText());
            button.performClick();
            require(Boolean.FALSE.equals(VbanService.lastEnabled), "Failed ON recovery did not request OFF");
            require(!prefs.getBoolean("enabled", true), "OFF recovery choice was not persisted");
        } else {
            require("TURN ON".contentEquals(button.getText())
                    && "ERROR".contentEquals(field(activity, "stateTitle", TextView.class).getText()),
                    "An OFF state without pending cleanup must direct the user to ON: " + status.getText());
        }
        System.out.println("MAIN_ACTIVITY_RETRY_PASS: " + scenario);
    }

    private static void configure(MemoryPrefs prefs) {
        AppConfig.save(prefs.edit().putString("receiver", "192.0.2.1")
                .putString("port", "6980").putString("stream", "vban-audio")
                .putString("frames", "256").putBoolean("authorized", true));
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) type.cast(field.get(owner));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
