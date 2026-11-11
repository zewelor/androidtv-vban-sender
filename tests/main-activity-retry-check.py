#!/usr/bin/env python3
"""Run the real MainActivity against controlled Android view adapters."""
import importlib.util
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/e2e/main-activity-retry"


def service_adapters():
    spec = importlib.util.spec_from_file_location("service_adapters", ROOT / "tests/service-e2e.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    stubs = dict(module.STUBS)
    stubs.pop("app/vbansender/app/MainActivity.java")
    stubs.pop("app/vbansender/app/EngineBackend.java")
    stubs["android/content/SharedPreferences.java"] = """
package android.content;
public interface SharedPreferences {
 String getString(String k,String d); boolean getBoolean(String k,boolean d);
 boolean contains(String k); Editor edit();
 default void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
 default void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
 interface OnSharedPreferenceChangeListener { void onSharedPreferenceChanged(SharedPreferences p,String key); }
 interface Editor { Editor putString(String k,String v); Editor putBoolean(String k,boolean v); Editor remove(String k); boolean commit(); default void apply(){commit();} }
}
"""
    stubs.update({
        "android/R.java": "package android; public class R { public static class drawable { public static final int ic_lock_silent_mode_off=1; } public static class attr { public static final int state_focused=1,state_pressed=2; } }",
        "android/app/Activity.java": """
package android.app;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
public class Activity extends android.content.Context {
 public void onCreate(Bundle b) { }
 public void onResume() { }
 public void onPause() { }
 public void setContentView(View v) { }
 public android.content.res.Resources getResources() { return new android.content.res.Resources(); }
 public void startActivity(Intent i) { }
 public void runOnUiThread(Runnable r) { r.run(); }
}
""",
        "android/content/res/Resources.java": "package android.content.res; public class Resources { public android.util.DisplayMetrics getDisplayMetrics(){return new android.util.DisplayMetrics();} }",
        "android/util/DisplayMetrics.java": "package android.util; public class DisplayMetrics { public float density=1.0f; }",
        "android/os/Bundle.java": "package android.os; public class Bundle { }",
        "android/graphics/Color.java": "package android.graphics; public class Color { public static final int WHITE=-1; public static int rgb(int r,int g,int b){return (r<<16)|(g<<8)|b;} }",
        "android/graphics/Typeface.java": "package android.graphics; public class Typeface { public static final int BOLD=1; }",
        "android/graphics/drawable/GradientDrawable.java": "package android.graphics.drawable; public class GradientDrawable { public void setColor(int c){} public void setCornerRadius(float r){} public void setStroke(int w,int c){} }",
        "android/graphics/drawable/StateListDrawable.java": "package android.graphics.drawable; public class StateListDrawable { public void addState(int[] s,GradientDrawable d){} }",
        "android/view/Gravity.java": "package android.view; public class Gravity { public static final int CENTER_HORIZONTAL=1,CENTER=2; }",
        "android/view/View.java": """
package android.view;
public class View {
 public static final int FOCUSABLE=1;
 private OnClickListener listener; private boolean enabled=true;
 public interface OnClickListener { void onClick(View v); }
 public void setOnClickListener(OnClickListener l){listener=l;}
 public boolean performClick(){if(listener!=null&&enabled){listener.onClick(this);return true;}return false;}
 public void requestFocus(){} public void setBackground(Object b){} public void setEnabled(boolean e){enabled=e;}
 public boolean isEnabled(){return enabled;}
}
""",
        "android/widget/TextView.java": """
package android.widget;
public class TextView extends android.view.View {
 private CharSequence text="";
 public TextView(android.content.Context c){}
 public void setText(CharSequence t){text=t;} public CharSequence getText(){return text;}
 public void setTextSize(float s){} public void setTextColor(int c){}
 public void setTypeface(android.graphics.Typeface t,int style){} public void setTypeface(android.graphics.Typeface t){}
 public void setGravity(int g){}
}
""",
        "android/widget/Button.java": """
package android.widget;
public class Button extends TextView {
 public Button(android.content.Context c){super(c);}
 public void setBackgroundTintList(Object l){}
}
""",
        "android/widget/LinearLayout.java": """
package android.widget;
public class LinearLayout extends android.view.View {
 public static final int VERTICAL=1;
 public static class LayoutParams { public int topMargin,bottomMargin; public LayoutParams(int w,int h){} }
 public LinearLayout(android.content.Context c){}
 public void setOrientation(int o){} public void setGravity(int g){} public void setPadding(int l,int t,int r,int b){}
 public void setBackgroundColor(int c){} public void addView(android.view.View v){} public void addView(android.view.View v,LayoutParams p){}
}
""",
        "app/vbansender/app/SettingsActivity.java": "package app.vbansender.app; public class SettingsActivity extends android.app.Activity { }",
        "app/vbansender/app/VbanService.java": """
package app.vbansender.app;
import android.content.Context; import android.content.Intent;
public class VbanService {
 public static Boolean lastEnabled;
 public static void setEnabled(Context c,boolean enabled){lastEnabled=enabled;AppConfig.accept(AppConfig.prefs(c),enabled);c.startForegroundService(new Intent(c,VbanService.class));}
}
""",
    })
    return stubs


def main():
    stubs = service_adapters()
    source = OUT / "adapters"
    classes = OUT / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    for name, contents in stubs.items():
        path = source / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents)
    actual = ["app/MainActivity.java", "app/AppConfig.java", "app/AudioController.java",
              "OutputConfig.java", "VbanPacket.java"]
    subprocess.run(["javac", "--release", "8", "-d", str(classes)]
                   + [str(p) for p in source.rglob("*.java")]
                   + [str(ROOT / "src/app/vbansender" / p) for p in actual]
                   + [str(ROOT / "tests" / p) for p in ("MainActivityRetryHarness.java", "MemoryPrefs.java")], check=True)
    for scenario in ("failed_off_cleanup", "failed_off_unconfigured", "retry_displayed_after_cleanup",
                     "ordinary_off", "failed_start", "off_error_without_pending"):
        subprocess.run(["java", "-cp", str(classes), "app.vbansender.app.MainActivityRetryHarness", scenario],
                       check=True, timeout=10)
    print("PASS: failed OFF retry, ordinary OFF→ON, failed-start OFF recovery, and state-specific error guidance")


if __name__ == "__main__":
    main()
