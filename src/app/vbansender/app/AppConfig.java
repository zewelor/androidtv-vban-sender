package app.vbansender.app;

import android.content.Context;
import android.content.SharedPreferences;
import app.vbansender.OutputConfig;

/** One saved receiver configuration; production has no device discovery. */
final class AppConfig {
    // Never held across remote work or controller callbacks.
    static final Object CONTROL_LOCK = new Object();

    static AudioController.Request request(SharedPreferences prefs) {
        synchronized (CONTROL_LOCK) {
            String token = prefs.getString("pending_off_token", "");
            if (prefs.getBoolean("cleanup_pending", false) && token.isEmpty()) {
                token = java.util.UUID.randomUUID().toString();
                save(prefs.edit().putString("pending_off_token", token));
            }
            return new AudioController.Request(prefs.getBoolean("enabled", false), token,
                    prefs.getBoolean("cleanup_pending", false));
        }
    }

    static void accept(SharedPreferences prefs, boolean enabled) {
        synchronized (CONTROL_LOCK) {
            SharedPreferences.Editor edit = prefs.edit().putBoolean("enabled", enabled);
            if (!enabled) edit.putString("pending_off_token", java.util.UUID.randomUUID().toString());
            save(edit);
        }
    }

    static String beginCleanup(SharedPreferences prefs) {
        synchronized (CONTROL_LOCK) {
            String token = request(prefs).offToken;
            if (token.isEmpty()) token = java.util.UUID.randomUUID().toString();
            save(prefs.edit().putString("pending_off_token", token).putBoolean("cleanup_pending", true));
            return token;
        }
    }

    static void finishCleanup(SharedPreferences prefs, String token) {
        synchronized (CONTROL_LOCK) {
            SharedPreferences.Editor edit = prefs.edit().remove("active_run")
                    .remove("original_surround").remove("cleanup_pending").remove("engine_snapshot");
            if (token.equals(prefs.getString("pending_off_token", ""))) edit.remove("pending_off_token");
            save(edit);
        }
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("vban", Context.MODE_PRIVATE);
    }

    static OutputConfig read(Context context) throws Exception {
        SharedPreferences prefs = prefs(context);
        return new OutputConfig(new String[] {
                prefs.getString("receiver", ""), prefs.getString("port", "6980"),
                prefs.getString("stream", "vban-audio"), prefs.getString("frames", "256")
        });
    }

    static boolean configured(Context context) {
        try {
            read(context);
            return context.getSharedPreferences("proof", Context.MODE_PRIVATE)
                    .getBoolean("authorized", false);
        } catch (Exception error) {
            return false;
        }
    }

    static boolean needsReconcile(Context context) {
        SharedPreferences prefs = prefs(context);
        return !prefs.getString("pending_off_token", "").isEmpty()
                || prefs.getBoolean("enabled", false)
                || prefs.getBoolean("cleanup_pending", false)
                || !prefs.getString("active_run", "").isEmpty()
                || prefs.contains("original_surround")
                || "STOPPING".equals(prefs.getString("controller_state", "OFF"));
    }

    static void save(SharedPreferences.Editor editor) {
        if (!editor.commit()) {
            throw new IllegalStateException("Could not save VBAN settings");
        }
    }
}
