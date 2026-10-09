package app.vbansender.app;

import android.content.Context;
import java.io.IOException;

/** Failed marker deletion must remain a cleanup obligation across backend replacement. */
public final class BackendCleanupHarness {
    private static boolean failMarker = true;
    private static String surround = "1";
    private static boolean ignoreRestore;
    private static int commands;
    public static String command(String command) throws Exception {
        commands++;
        if (command.startsWith("rm -f ")) {
            if (failMarker) throw new IOException("Transport failed before marker deletion");
            return "";
        }
        if (command.startsWith("if [ -f ")) return "";
        if (command.equals("settings get global encoded_surround_output")) return surround;
        if (command.startsWith("settings put global encoded_surround_output ")) {
            if (!ignoreRestore) surround = command.substring(command.lastIndexOf(' ') + 1);
            return "";
        }
        if (command.equals("settings delete global encoded_surround_output")) { surround = "null"; return ""; }
        if (command.equals("id")) return "uid=2000(shell)";
        throw new AssertionError("Unexpected shell operation: " + command);
    }

    public static void main(String[] args) throws Exception {
        MemoryPrefs prefs = new MemoryPrefs(); Context.prefs = prefs;
        String run = "0123456789abcdef0123456789abcdef";
        AppConfig.save(prefs.edit().putString("receiver", "192.0.2.1")
                .putString("active_run", run).putString("original_surround", "0"));
        try { new EngineBackend(new Context()).stop(); throw new AssertionError("Stop should fail"); }
        catch (IOException expected) { }
        require(prefs.getBoolean("cleanup_pending", false), "failed Stop did not persist its obligation");
        require(run.equals(prefs.getString("active_run", "")) && prefs.contains("original_surround"), "failed cleanup discarded ownership");
        int before = commands;
        try { new EngineBackend(new Context()).start(() -> true); throw new AssertionError("replacement should not adopt before cleanup"); }
        catch (IllegalStateException expected) { require(expected.getMessage().contains("cleanup"), "unrelated failure"); }
        require(commands == before, "replacement accessed the engine before resolving cleanup");
        failMarker = false;
        ignoreRestore = true;
        try { new EngineBackend(new Context()).stop(); throw new AssertionError("unconfirmed restore should fail"); }
        catch (IllegalStateException expected) { }
        require(prefs.getBoolean("cleanup_pending", false) && prefs.contains("original_surround"),
                "unconfirmed restoration discarded its cleanup obligation");
        ignoreRestore = false;
        new EngineBackend(new Context()).stop();
        require("0".equals(surround) && !prefs.contains("active_run") && !prefs.contains("original_surround")
                && !prefs.contains("cleanup_pending"), "successful OFF did not restore and clear ownership");
        AppConfig.save(prefs.edit().putBoolean("cleanup_pending", true));
        require(AppConfig.needsReconcile(new Context()), "saved cleanup obligation was ignored at reconciliation");
        new EngineBackend(new Context()).stop();
        require(!AppConfig.needsReconcile(new Context()), "confirmed OFF did not finish reconciliation");
        AppConfig.save(prefs.edit().putString("original_surround", "null"));
        new EngineBackend(new Context()).stop();
        require("null".equals(surround), "absent original surround setting was not restored");
        System.out.println("BACKEND_CLEANUP_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
