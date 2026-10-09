#!/usr/bin/env python3
"""Actual service/controller/dispatch code with controlled Android lifecycle adapters."""
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/e2e/service"
STUBS = {
    "android/content/SharedPreferences.java": """
package android.content;
public interface SharedPreferences {
 String getString(String k,String d); boolean getBoolean(String k,boolean d);
 boolean contains(String k); Editor edit();
 interface Editor { Editor putString(String k,String v); Editor putBoolean(String k,boolean v); Editor remove(String k); boolean commit(); }
}
""",
    "android/content/Context.java": """
package android.content;
import java.util.*;
public class Context {
 public static final int MODE_PRIVATE=0;
 public static SharedPreferences prefs;
 public static final List<Intent> commands=new ArrayList<>();
 public SharedPreferences getSharedPreferences(String n,int m) { return prefs; }
 public Context getApplicationContext() { return this; }
 public android.content.pm.ApplicationInfo getApplicationInfo() { return new android.content.pm.ApplicationInfo(); }
 public Object startForegroundService(Intent i) { commands.add(i); return null; }
 public <T> T getSystemService(Class<T> c) { return c.cast(new android.app.NotificationManager()); }
}
""",
    "android/content/pm/ApplicationInfo.java": "package android.content.pm; public class ApplicationInfo { public String sourceDir=\"/data/app/example/base.apk\"; }",
    "android/content/Intent.java": """
package android.content;
import java.util.*;
public class Intent {
 public static final int FLAG_ACTIVITY_NEW_TASK=1;
 private final Map<String,Boolean> extras=new HashMap<>();
 public Intent(Context c,Class<?> t) { }
 public Intent addFlags(int f) { return this; }
 public Intent putExtra(String k,boolean v) { extras.put(k,v); return this; }
 public boolean hasExtra(String k) { return extras.containsKey(k); }
 public boolean getBooleanExtra(String k,boolean d) { return extras.getOrDefault(k,d); }
}
""",
    "android/os/Looper.java": "package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }",
    "android/os/IBinder.java": "package android.os; public interface IBinder { }",
    "android/os/Handler.java": """
package android.os;
import java.util.concurrent.*;
public class Handler {
 public static final BlockingQueue<Runnable> queue=new LinkedBlockingQueue<>();
 public Handler(Looper l) { }
 public boolean post(Runnable r) { queue.add(r); return true; }
 public boolean postDelayed(Runnable r,long ms) { return true; }
 public void removeCallbacks(Runnable r) { queue.remove(r); }
 public void removeCallbacksAndMessages(Object token) { queue.clear(); }
}
""",
    "android/util/Log.java": "package android.util; public class Log { public static int i(String t,String s){return 0;} public static int e(String t,String s,Throwable e){return 0;} }",
    "android/R.java": "package android; public class R { public static class drawable { public static final int ic_lock_silent_mode_off=1; } }",
    "android/app/Notification.java": """
package android.app;
public class Notification {
 public String text;
 public static class Builder {
  private final Notification n=new Notification();
  public Builder(android.content.Context c,String id) { }
  public Builder setSmallIcon(int i){return this;} public Builder setContentTitle(String s){return this;}
  public Builder setContentText(String s){n.text=s;return this;} public Builder setOngoing(boolean b){return this;}
  public Builder setContentIntent(PendingIntent i){return this;} public Notification build(){return n;}
 }
}
""",
    "android/app/NotificationChannel.java": "package android.app; public class NotificationChannel { public NotificationChannel(String a,String b,int c) { } }",
    "android/app/NotificationManager.java": "package android.app; public class NotificationManager { public static final int IMPORTANCE_LOW=1; public static String text; public void createNotificationChannel(NotificationChannel c) { } public void notify(int id,Notification n){text=n.text;} }",
    "android/app/PendingIntent.java": "package android.app; public class PendingIntent { public static final int FLAG_IMMUTABLE=1,FLAG_UPDATE_CURRENT=2; public static PendingIntent getActivity(android.content.Context c,int id,android.content.Intent i,int f){return new PendingIntent();} }",
    "android/app/Service.java": """
package android.app;
public class Service extends android.content.Context {
 public static final int STOP_FOREGROUND_REMOVE=1, START_NOT_STICKY=2;
 public int serverStart; public boolean stopped,foreground=true;
 public void onCreate() { } public void onDestroy() { }
 public int onStartCommand(android.content.Intent i,int flags,int id){return 0;}
 public android.os.IBinder onBind(android.content.Intent i){return null;}
 public void startForeground(int id,Notification n){foreground=true;}
 public void stopForeground(int f){foreground=false;}
 public boolean stopSelfResult(int id){if(id!=serverStart)return false;stopped=true;onDestroy();return true;}
}
""",
    "app/vbansender/app/MainActivity.java": "package app.vbansender.app; public class MainActivity { }",
    "app/vbansender/app/EngineBackend.java": """
package app.vbansender.app;
public class EngineBackend implements AudioController.Backend {
 public static AudioController.Backend delegate;
 public EngineBackend(android.content.Context c) { }
 public void start(java.util.function.BooleanSupplier e)throws Exception{delegate.start(e);}
 public void stop()throws Exception{delegate.stop();} public void check()throws Exception{delegate.check();}
}
""",
}


def main():
    source = OUT / "adapters"
    classes = OUT / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    for name, contents in STUBS.items():
        path = source / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents)
    actual = ["app/AudioController.java", "app/VbanService.java", "app/AppConfig.java",
              "OutputConfig.java", "VbanPacket.java"]
    subprocess.run(["javac", "--release", "8", "-d", str(classes)]
                   + [str(p) for p in source.rglob("*.java")]
                   + [str(ROOT / "src/app/vbansender" / p) for p in actual]
                   + [str(ROOT / "tests" / p) for p in ("ServiceHarness.java", "MemoryPrefs.java")], check=True)
    results = {}
    for scenario in ("delayed_commands", "failed_stop", "stale_off", "shutdown_rejected",
                     "off_on_during_check", "replacement_during_start", "replacement_with_pending_off",
                     "replacement_cleanup_fails"):
        result = subprocess.run(["java", "-cp", str(classes), "app.vbansender.app.ServiceHarness", scenario],
                                text=True, capture_output=True, timeout=15)
        results[scenario] = {"exit": result.returncode, "stdout": result.stdout, "stderr": result.stderr}
    report = {"result": "PASS" if all(r["exit"] == 0 for r in results.values()) else "FAIL", "scenarios": results,
              "scope": "Production service/controller/dispatch with lifecycle adapters; no Android device proof"}
    (OUT / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    assert report["result"] == "PASS", results
    print("PASS: service dispatch, stale OFF, cleanup errors, shutdown, OFF/ON, service replacement")


if __name__ == "__main__":
    main()
