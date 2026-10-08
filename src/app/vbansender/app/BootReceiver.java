package app.vbansender.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Dispatches a saved ON choice once; no periodic supervisor. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || !AppConfig.needsReconcile(context)) {
            return;
        }
        try {
            context.startForegroundService(new Intent(context, VbanService.class));
        } catch (RuntimeException error) {
            Log.e("VBAN", "Boot dispatch failed", error);
            AppConfig.save(AppConfig.prefs(context).edit().putString("last_error",
                    "Boot dispatch failed: " + error.getMessage()));
        }
    }
}
