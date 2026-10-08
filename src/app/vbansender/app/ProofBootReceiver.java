package app.vbansender.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts only the explicitly enabled, finite heartbeat proof. */
public final class ProofBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || !context.getSharedPreferences("proof", Context.MODE_PRIVATE)
                    .getBoolean("boot_enabled", false)) {
            return;
        }
        Log.i("VBAN-PROOF", "BOOT_COMPLETED: finite self-ADB proof requested");
        try {
            context.startForegroundService(new Intent(context, ProofService.class));
        } catch (RuntimeException error) {
            Log.e("VBAN-PROOF", "Cannot start boot proof", error);
            context.getSharedPreferences("proof", Context.MODE_PRIVATE).edit()
                    .putString("status", "FAIL boot: " + error.getMessage()).apply();
        }
    }
}
