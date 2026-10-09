package app.vbansender.app;

import android.content.Context;
import android.content.SharedPreferences;
import app.vbansender.OutputConfig;

/** One saved receiver configuration; production has no device discovery. */
final class AppConfig {
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
        return prefs.getBoolean("enabled", false)
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
