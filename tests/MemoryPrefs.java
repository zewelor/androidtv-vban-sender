package app.vbansender.app;

import android.content.SharedPreferences;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/** Thread-safe preferences adapter shared by service and shell-backend scenarios. */
final class MemoryPrefs implements SharedPreferences {
    private final Map<String,Object> values = new HashMap<>();
    final List<String> states = Collections.synchronizedList(new ArrayList<>());
    public synchronized String getString(String key,String fallback) { return (String) values.getOrDefault(key,fallback); }
    public synchronized boolean getBoolean(String key,boolean fallback) { return (Boolean) values.getOrDefault(key,fallback); }
    public synchronized boolean contains(String key) { return values.containsKey(key); }
    public Editor edit() {
        return new Editor() {
            private final Map<String,Object> updates = new HashMap<>();
            public Editor putString(String k,String v) { updates.put(k,v); return this; }
            public Editor putBoolean(String k,boolean v) { updates.put(k,v); return this; }
            public Editor remove(String k) { updates.put(k,null); return this; }
            public boolean commit() {
                synchronized (MemoryPrefs.this) {
                    if (updates.containsKey("controller_state")) states.add((String) updates.get("controller_state"));
                    updates.forEach((k,v) -> { if (v == null) values.remove(k); else values.put(k,v); });
                }
                return true;
            }
        };
    }
}
