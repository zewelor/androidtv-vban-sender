package app.vbansender.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.CancellationException;

/** Owns serialized user requests; checks run on the same worker as Start and Stop. */
public final class VbanService extends Service {
    private static final String EXTRA_ENABLED = "enabled";
    private AudioController controller;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int latestStart;
    private volatile boolean destroyed;
    private final Runnable healthCheck = new Runnable() {
        @Override
        public void run() {
            controller.check();
            main.postDelayed(this, 15000);
        }
    };

    /** Saves the user's choice without guessing the controller's progress or clearing errors. */
    static void setEnabled(Context context, boolean enabled) {
        AppConfig.save(AppConfig.prefs(context).edit().putBoolean("enabled", enabled));
        context.startForegroundService(new Intent(context, VbanService.class)
                .putExtra(EXTRA_ENABLED, enabled));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("vban", "VBAN audio routing",
                NotificationManager.IMPORTANCE_LOW));
        startForeground(2, notification("Preparing audio routing"));
        controller = new AudioController(new EngineBackend(this), new AudioController.Listener() {
            @Override
            public void onState(final AudioController.State state) {
                if (destroyed) return;
                Log.i("VBAN", "Controller " + state);
                SharedPreferences prefs = AppConfig.prefs(VbanService.this);
                SharedPreferences.Editor update = prefs.edit().putString("controller_state", state.name());
                if (state == AudioController.State.ON || state == AudioController.State.OFF
                        && !prefs.getBoolean("enabled", false)) {
                    update.remove("last_error");
                }
                AppConfig.save(update);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        publishCurrentState();
                    }
                });
            }

            @Override
            public void onError(Exception error) {
                if (destroyed || error instanceof CancellationException) {
                    return;
                }
                Log.e("VBAN", "Audio control failed", error);
                AppConfig.save(AppConfig.prefs(VbanService.this).edit().putString("last_error",
                        error.getClass().getSimpleName() + ": " + error.getMessage()));
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        publishCurrentState();
                    }
                });
            }
        });
        main.postDelayed(healthCheck, 15000);
    }

    /** Main-thread callbacks use current cleanup proof, never a historical OFF event. */
    private void publishCurrentState() {
        if (destroyed) return;
        SharedPreferences prefs = AppConfig.prefs(this);
        boolean off = !prefs.getBoolean("enabled", false) && controller.isOff();
        if (off && prefs.contains("last_error")) {
            AppConfig.save(prefs.edit().remove("last_error"));
        }
        String state = prefs.getString("controller_state", "OFF");
        boolean failed = !prefs.getString("last_error", "").isEmpty();
        getSystemService(NotificationManager.class).notify(2,
                notification(failed ? "Error — open VBAN for details"
                        : "ON".equals(state) ? "Automatic audio routing enabled" : state));
        if (off && stopSelfResult(latestStart)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return new Notification.Builder(this, "vban")
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentTitle("VBAN").setContentText(text).setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        latestStart = startId;
        boolean enabled = intent != null && intent.hasExtra(EXTRA_ENABLED)
                ? intent.getBooleanExtra(EXTRA_ENABLED, false)
                : AppConfig.prefs(this).getBoolean("enabled", false);
        controller.request(enabled);
        publishCurrentState();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        controller.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
