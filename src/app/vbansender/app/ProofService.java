package app.vbansender.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/** Bounded boot proof: at most six local connection attempts; no audio or wake lock. */
public final class ProofService extends Service {
    private boolean running;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("proof", "Startup test",
                NotificationManager.IMPORTANCE_LOW));
        startForeground(1, new Notification.Builder(this, "proof")
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentTitle("VBAN — startup test")
                .setContentText("Bounded local ADB test; audio output unchanged").build());
    }

    private void report(String message) {
        Log.i("VBAN-PROOF", message);
        getSharedPreferences("proof", MODE_PRIVATE).edit().putString("status", message).apply();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, final int startId) {
        if (!running) {
            running = true;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (int attempt = 1; attempt <= 6; attempt++) {
                            try {
                                report("Boot: local ADB attempt " + attempt + "/6");
                                String result = SelfAdbProof.run(ProofService.this,
                                        new SelfAdbProof.Progress() {
                                            @Override
                                            public void report(String message) {
                                                ProofService.this.report(message);
                                            }
                                        }, 5000);
                                report("Boot " + result);
                                return;
                            } catch (SelfAdbProof.ConnectionUnavailable error) {
                                if (attempt == 6) {
                                    throw error;
                                }
                                report("ADB not yet available: " + error.getMessage());
                                Thread.sleep(attempt * 2000L);
                            }
                        }
                    } catch (Exception error) {
                        Log.e("VBAN-PROOF", "Boot proof failed", error);
                        report("FAIL boot: " + error.getClass().getSimpleName() + ": " + error.getMessage());
                    } finally {
                        stopSelf();
                    }
                }
            }, "boot-self-adb-proof").start();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
