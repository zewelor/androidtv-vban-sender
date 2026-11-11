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
        } else if (scenario.equals("ordinary_off")) {
            AppConfig.save(prefs.edit().putBoolean("enabled", false));
        } else if (scenario.equals("failed_start")) {
            AppConfig.save(prefs.edit().putBoolean("enabled", true)
                    .putString("last_error", "IOException: start failed"));
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
            require(status.getText().toString().contains("Retry OFF to finish cleanup."),
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
        } else if (scenario.equals("ordinary_off")) {
            require("OFF".contentEquals(button.getText()), "Ordinary OFF label changed");
            button.performClick();
            require(Boolean.TRUE.equals(VbanService.lastEnabled), "Ordinary OFF did not request ON");
            require(prefs.getBoolean("enabled", false), "ON choice was not persisted");
        } else if (scenario.equals("failed_start")) {
            require(status.getText().toString().contains("Turn OFF, then ON to retry."),
                    "Failed start instructions must match saved ON state: " + status.getText());
            button.performClick();
            require(Boolean.FALSE.equals(VbanService.lastEnabled), "Failed ON recovery did not request OFF");
            require(!prefs.getBoolean("enabled", true), "OFF recovery choice was not persisted");
        } else {
            require(status.getText().toString().contains("Turn ON to retry."),
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
