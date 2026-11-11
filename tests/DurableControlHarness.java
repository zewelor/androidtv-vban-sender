package app.vbansender.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Two fresh JVMs retain only preferences and a simulated surviving shell engine. */
public final class DurableControlHarness {
    private static final String ORIGINAL_RUN = "0123456789abcdef0123456789abcdef";
    private static final CountDownLatch CHECKING = new CountDownLatch(1);
    private static volatile boolean block;
    private static volatile boolean active = true;
    private static volatile String currentRun = ORIGINAL_RUN;
    private static String surround = "1";
    private static volatile int stops;
    private static int mutations;

    public static String command(String command) throws Exception {
        if (command.equals("id")) return "uid=2000(shell)";
        if (command.startsWith("if [ -f ") && command.contains("engine-state.txt")) {
            if (block) {
                CHECKING.countDown();
                new CountDownLatch(1).await();
            }
            return active ? "run=" + currentRun + " uid=2000 pid=123 state=SENDING message=none" : "";
        }
        if (command.contains("/cmdline")) {
            return "app_process\n/\napp.vbansender.AudioEngine\n" + currentRun + "\n";
        }
        if (command.contains("echo ENABLED")) return "ENABLED";
        mutations++;
        if (command.startsWith("CLASSPATH=")
                && command.endsWith(" app_process / app.vbansender.EnginePreparation")) return "";
        if (command.startsWith("rm -f /data/local/tmp/vban-sender/enabled-")) {
            active = false;
            stops++;
            return "";
        }
        if (command.startsWith("mkdir -p ") || command.startsWith("touch ")) return "";
        if (command.equals("settings get global encoded_surround_output")) return surround;
        if (command.startsWith("settings put global encoded_surround_output ")) {
            surround = command.substring(command.lastIndexOf(' ') + 1);
            return "";
        }
        if (command.startsWith("CLASSPATH=")) {
            Matcher matcher = Pattern.compile(" ([a-f0-9]{32}) </dev/null").matcher(command);
            require(matcher.find(), "Launch has no run identity");
            currentRun = matcher.group(1);
            active = true;
            return "";
        }
        throw new AssertionError("Unexpected shell command: " + command);
    }

    public static void main(String[] args) throws Exception {
        MemoryPrefs prefs = new MemoryPrefs();
        Context.prefs = prefs;
        Context context = new Context();
        if (args[0].startsWith("capture")) {
            capture(context, prefs, args[1], args[0].equals("capture-no-dispatch"));
        } else {
            recover(context, prefs, args[1]);
        }
    }

    private static void capture(Context context, MemoryPrefs prefs, String path,
            boolean beforeDispatch) throws Exception {
        AppConfig.save(prefs.edit().putBoolean("enabled", true).putString("receiver", "192.0.2.1")
                .putString("active_run", ORIGINAL_RUN).putString("original_surround", "0"));
        VbanService service = start(context, 1);
        await(() -> "ON".equals(prefs.getString("controller_state", "")), "initial ON");
        Field field = VbanService.class.getDeclaredField("controller");
        field.setAccessible(true);
        block = true;
        ((AudioController) field.get(service)).check();
        require(CHECKING.await(3, TimeUnit.SECONDS), "Health check did not block");
        VbanService.setEnabled(context, false);
        VbanService.setEnabled(context, true);
        if (!beforeDispatch) {
            service.serverStart = 2;
            service.onStartCommand(Context.commands.get(0), 0, 2);
            service.serverStart = 3;
            service.onStartCommand(Context.commands.get(1), 0, 3);
        }
        Properties saved = new Properties();
        saved.setProperty("enabled", String.valueOf(prefs.getBoolean("enabled", false)));
        saved.setProperty("cleanup_pending", String.valueOf(prefs.getBoolean("cleanup_pending", false)));
        for (String key : new String[]{"receiver", "active_run", "original_surround",
                "controller_state", "pending_off_token"}) {
            saved.setProperty(key, prefs.getString(key, ""));
        }
        try (OutputStream output = Files.newOutputStream(Paths.get(path))) {
            saved.store(output, "Snapshot at process death");
        }
        require(prefs.getBoolean("enabled", false) && !prefs.getBoolean("cleanup_pending", false)
                && mutations == 0, "Expected accepted requests before any cleanup");
        System.out.println("Accepted OFF then ON; old engine survives; terminating JVM before cleanup");
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }

    private static void recover(Context context, MemoryPrefs prefs, String path) throws Exception {
        Properties saved = new Properties();
        try (InputStream input = Files.newInputStream(Paths.get(path))) { saved.load(input); }
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : saved.stringPropertyNames()) {
            if (key.equals("enabled") || key.equals("cleanup_pending")) {
                edit.putBoolean(key, Boolean.parseBoolean(saved.getProperty(key)));
            } else {
                edit.putString(key, saved.getProperty(key));
            }
        }
        AppConfig.save(edit);
        VbanService service = start(context, 1);
        await(() -> stops == 1 && !ORIGINAL_RUN.equals(prefs.getString("active_run", ""))
                && "ON".equals(prefs.getString("controller_state", "")),
                "Fresh JVM adopted old engine without performing accepted OFF");
        require(active && prefs.getString("pending_off_token", "").isEmpty()
                && !prefs.getBoolean("cleanup_pending", false), "Cleanup not acknowledged before new ON");
        service.onDestroy();
        System.out.println("DURABLE_CONTROL_PASS: fresh process cleaned before new ON");
    }

    private static VbanService start(Context context, int id) {
        VbanService service = new VbanService();
        service.onCreate();
        service.serverStart = id;
        service.onStartCommand(new Intent(context, VbanService.class), 0, id);
        return service;
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + 3000000000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        require(condition.getAsBoolean(), message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
