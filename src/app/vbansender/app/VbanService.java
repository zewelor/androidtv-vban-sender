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
import android.os.SystemClock;
import android.util.Log;
import java.util.concurrent.CancellationException;

/** Owns serialized user requests; checks run on the same worker as Start and Stop. */
public final class VbanService extends Service {
    private AudioController controller;
    private NetworkMonitor network;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int latestStart;
    private volatile boolean destroyed;
    private final Runnable networkRetry = new Runnable() {
        @Override public void run() { controller.retryNetwork(); }
    };
    private final Runnable countdown = new Runnable() {
        @Override public void run() { publishCurrentState(); }
    };
    private final Runnable healthCheck = new Runnable() {
        @Override
        public void run() {
            controller.check();
            main.postDelayed(this, 15000);
        }
    };

    /** Saves the user's choice without guessing the controller's progress or clearing errors. */
    static void setEnabled(Context context, boolean enabled) {
        AppConfig.accept(AppConfig.prefs(context), enabled);
        context.startForegroundService(new Intent(context, VbanService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("vban", "VBAN audio routing",
                NotificationManager.IMPORTANCE_LOW));
        startForeground(2, notification("Preparing audio routing"));
        network = new NetworkMonitor(this, main, new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                controller.retryNetwork();
                controller.check();
            }
        });
        controller = new AudioController(new EngineBackend(this,
                new java.util.function.Predicate<java.net.InetAddress>() {
                    @Override public boolean test(java.net.InetAddress address) {
                        return network.hasRoute(address);
                    }
                }),
                new AudioController.Listener() {
            @Override
            public void onState(final AudioController.State state) {
                if (destroyed) return;
                Log.i("VBAN", "Controller " + state);
                SharedPreferences prefs = AppConfig.prefs(VbanService.this);
                SharedPreferences.Editor update = prefs.edit().putString("controller_state", state.name());
                if (state != AudioController.State.WAITING_NETWORK) update.remove("network_retry_at_uptime_ms");
                if (state == AudioController.State.ON || state == AudioController.State.WAITING_NETWORK
                        || state == AudioController.State.OFF
                        && !prefs.getBoolean("enabled", false)) {
                    update.remove("last_error");
                }
                AppConfig.save(update);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (state != AudioController.State.WAITING_NETWORK) main.removeCallbacks(networkRetry);
                        publishCurrentState();
                    }
                });
            }

            @Override public void onNetworkWait(final long retryMillis) {
                if (destroyed) return;
                AppConfig.save(AppConfig.prefs(VbanService.this).edit()
                        .putString("network_retry_seconds", Long.toString(retryMillis / 1000)));
                main.post(new Runnable() {
                    @Override public void run() {
                        if (destroyed) return;
                        main.removeCallbacks(networkRetry);
                        SharedPreferences prefs = AppConfig.prefs(VbanService.this);
                        if (prefs.getBoolean("enabled", false)
                                && "WAITING_NETWORK".equals(prefs.getString("controller_state", ""))) {
                            long retryAt = SystemClock.uptimeMillis() + retryMillis;
                            AppConfig.save(prefs.edit().putString("network_retry_at_uptime_ms", Long.toString(retryAt)));
                            main.postAtTime(networkRetry, retryAt);
                        }
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
        }, new AudioController.Requests() {
            @Override public AudioController.Request read() {
                return AppConfig.request(AppConfig.prefs(VbanService.this));
            }
        });
        main.postDelayed(healthCheck, 15000);
    }

    /** Main-thread callbacks use current cleanup proof, never a historical OFF event. */
    private void publishCurrentState() {
        if (destroyed) return;
        main.removeCallbacks(countdown);
        SharedPreferences prefs = AppConfig.prefs(this);
        boolean off = !prefs.getBoolean("enabled", false) && controller.isOff();
        if (off && prefs.contains("last_error")) {
            AppConfig.save(prefs.edit().remove("last_error"));
        }
        String state = prefs.getString("controller_state", "OFF");
        boolean failed = !prefs.getString("last_error", "").isEmpty();
        boolean waiting = prefs.getBoolean("enabled", false) && "WAITING_NETWORK".equals(state) && !failed;
        getSystemService(NotificationManager.class).notify(2,
                notification(failed ? "Error — open VBAN for details"
                        : waiting ? "Waiting for network — retry in "
                                + AppConfig.networkRetrySeconds(prefs, SystemClock.uptimeMillis()) + " s"
                        : "ON".equals(state) ? "Automatic audio routing enabled" : state));
        if (waiting) main.postDelayed(countdown, 1000);
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
        controller.reconcileSaved();
        publishCurrentState();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        network.close();
        main.removeCallbacksAndMessages(null);
        controller.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
