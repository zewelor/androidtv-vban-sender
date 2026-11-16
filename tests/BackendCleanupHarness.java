package app.vbansender.app;

import android.content.Context;
import java.io.IOException;

/** Failed marker deletion must remain a cleanup obligation across backend replacement. */
public final class BackendCleanupHarness {
    private static boolean failMarker = true;
    private static String surround = "1";
    private static boolean ignoreRestore;
    private static int commands;
    private static boolean slow;
    private static long nanos;
    private static boolean newerOffOnRestore;
    private static boolean health;
    private static String healthStatus;
    private static boolean healthAlive = true;
    private static boolean starting;
    private static boolean staleStop;
    private static boolean markerCreated;
    private static boolean foreignOwner;
    private static int startupSurroundWrites;
    private static String startupPreviousStatus = "";
    private static int startupLaunches;
    private static Runnable beforeIdentity;
    private static Runnable beforeHealthRead;
    public static String command(String command) throws Exception {
        return command(command, 10000);
    }

    public static String command(String command, int timeout) throws Exception {
        commands++;
        if (command.equals("id") && beforeIdentity != null) {
            Runnable action = beforeIdentity; beforeIdentity = null; action.run();
        }
        if (starting) {
            if (command.equals("id")) return "uid=2000(shell)";
            if (command.startsWith("if [ -f ")) {
                return markerCreated ? "run=" + Context.prefs.getString("active_run", "")
                        + " uid=2000 pid=123 state=IDLE message=none" : startupPreviousStatus;
            }
            if (command.contains("/cmdline")) return !markerCreated && !startupPreviousStatus.isEmpty() ? ""
                    : "app.vbansender.AudioEngine\n" + Context.prefs.getString("active_run", "");
            if (command.startsWith("rm -f ")) return "";
            if (command.startsWith("mkdir -p ")) return "";
            if (command.equals("settings get global encoded_surround_output")) return "0";
            if (command.startsWith("settings put global encoded_surround_output ")) {
                startupSurroundWrites++;
                return "";
            }
            if (command.startsWith("CLASSPATH=")
                    && command.endsWith(" app_process / app.vbansender.EnginePreparation")) {
                if (foreignOwner) throw new IOException("Capture lock is held");
                require(!markerCreated, "A newly requested Stop could be erased after enabling");
                staleStop = false;
                return "";
            }
            if (command.startsWith("touch /data/local/tmp/vban-sender/enabled-")) {
                require(!staleStop, "Stale Stop must be cleared before enabling a new run");
                markerCreated = true;
                return "";
            }
            if (command.startsWith("CLASSPATH=")) {
                if (foreignOwner) throw new IOException("Capture lock is held");
                startupLaunches++;
                return "";
            }
            throw new AssertionError("Unexpected startup operation: " + command);
        }
        if (health && command.contains("then echo ENABLED")) return "ENABLED";
        if (health && command.startsWith("if [ -f ")) {
            if (beforeHealthRead != null) { Runnable action = beforeHealthRead; beforeHealthRead = null; action.run(); }
            return healthStatus;
        }
        if (health && command.contains("/cmdline")) {
            if (!healthAlive) return "";
            return "app.vbansender.AudioEngine\n0123456789abcdef0123456789abcdef";
        }
        if (slow) {
            long remaining = 30000000000L - nanos;
            require(remaining > 0 && timeout <= Math.min(10000, remaining / 1000000L),
                    "Stop command exceeded remaining overall budget");
            nanos += Math.min(9000000000L, timeout * 1000000L);
            if (command.startsWith("if [ -f ")) return "run=0123456789abcdef0123456789abcdef uid=2000 pid=123 state=SENDING message=none";
            if (command.contains("/cmdline")) return "app.vbansender.AudioEngine\n0123456789abcdef0123456789abcdef";
        }
        if (command.startsWith("rm -f ")) {
            if (failMarker) throw new IOException("Transport failed before marker deletion");
            return "";
        }
        if (command.startsWith("if [ -f ")) return "";
        if (command.equals("settings get global encoded_surround_output")) {
            if (newerOffOnRestore) {
                newerOffOnRestore = false;
                AppConfig.accept(Context.prefs, false);
                AppConfig.accept(Context.prefs, true);
            }
            return surround;
        }
        if (command.startsWith("settings put global encoded_surround_output ")) {
            if (!ignoreRestore) surround = command.substring(command.lastIndexOf(' ') + 1);
            return "";
        }
        if (command.equals("settings delete global encoded_surround_output")) { surround = "null"; return ""; }
        if (command.equals("id")) return "uid=2000(shell)";
        throw new AssertionError("Unexpected shell operation: " + command);
    }

    public static void main(String[] args) throws Exception {
        missingNetworkDoesNotLaunch();
        unobservedTerminalFailureRequiresRecovery();
        fatalFailureSurvivesOwnerRecreation();
        obsoleteFailureDoesNotBlockRecoveredRequest();
        if (args.length == 1 && "startup".equals(args[0])) {
            startClearsStaleStopBeforeEnabling();
            return;
        }
        if (args.length == 1 && "foreign".equals(args[0])) {
            startPreservesForeignStop();
            return;
        }
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
        // OFF arriving during restoration must survive the older Stop's commit.
        AppConfig.save(prefs.edit().putString("active_run", run).putString("original_surround", "0"));
        AppConfig.accept(prefs, false);
        String oldToken = AppConfig.request(prefs).offToken;
        newerOffOnRestore = true;
        new EngineBackend(new Context()).stop();
        require(!AppConfig.request(prefs).offToken.isEmpty()
                && !oldToken.equals(AppConfig.request(prefs).offToken), "older Stop erased a newer OFF");
        before = commands;
        new EngineBackend(new Context()).stop();
        require(commands == before && AppConfig.request(prefs).offToken.isEmpty(), "second obligation should acknowledge locally");

        AppConfig.save(prefs.edit().putString("active_run", run).putString("original_surround", "0"));
        slow = true; nanos = 0; before = commands;
        try { new EngineBackend(new Context(), () -> nanos).stop(); throw new AssertionError("Stop must exhaust overall budget"); }
        catch (java.net.SocketTimeoutException expected) { }
        require(nanos == 30000000000L && commands - before == 4, "Stop did not obey one budget");
        require(prefs.getBoolean("cleanup_pending", false) && prefs.contains("active_run")
                && prefs.contains("original_surround"), "timeout discarded recovery state");
        checkHeartbeat(prefs, run);
        startClearsStaleStopBeforeEnabling();
        startPreservesForeignStop();
        System.out.println("BACKEND_CLEANUP_PASS");
    }

    private static void obsoleteFailureDoesNotBlockRecoveredRequest() throws Exception {
        MemoryPrefs prefs = new MemoryPrefs(); Context.prefs = prefs;
        AppConfig.save(prefs.edit().putBoolean("enabled", true).putString("receiver", "192.0.2.1"));
        java.util.concurrent.atomic.AtomicBoolean currentStart = new java.util.concurrent.atomic.AtomicBoolean(true);
        beforeIdentity = () -> {
            AppConfig.accept(prefs, false); AppConfig.accept(prefs, true);
            currentStart.set(false);
            throw new IllegalStateException("Obsolete start failed after newer OFF/ON");
        };
        try { new EngineBackend(new Context()).start(currentStart::get); throw new AssertionError("Expected old start failure"); }
        catch (IllegalStateException expected) { }
        require(prefs.getString("manual_recovery_error", "").isEmpty(), "Obsolete start reinstated a cleared recovery gate");
        new EngineBackend(new Context()).stop();

        String run = "0123456789abcdef0123456789abcdef";
        AppConfig.save(prefs.edit().putString("active_run", run));
        health = true; healthAlive = true;
        healthStatus = "run=" + run + " uid=2000 pid=123 state=ERROR message=Obsolete engine failure";
        beforeHealthRead = () -> { AppConfig.accept(prefs, false); AppConfig.accept(prefs, true); };
        try { new EngineBackend(new Context()).check(); throw new AssertionError("Expected old health failure"); }
        catch (IllegalStateException expected) { }
        require(prefs.getString("manual_recovery_error", "").isEmpty(), "Obsolete health result blocked the newer accepted request");
        try { new EngineBackend(new Context()).check(); throw new AssertionError("Expected in-flight health failure"); }
        catch (IllegalStateException expected) { }
        require(prefs.getString("manual_recovery_error", "").isEmpty(), "Check beginning after accepted OFF reinstated recovery gate");
        healthAlive = false;
        boolean originalFailMarker = failMarker; failMarker = false;
        try { new EngineBackend(new Context()).stop(); }
        finally { failMarker = originalFailMarker; }
        health = false; healthAlive = true;
        starting = true; markerCreated = false; staleStop = true;
        try { new EngineBackend(new Context()).start(() -> true); }
        finally { starting = false; }
        require(markerCreated, "New ON did not start after stale-result cleanup");
    }

    private static void fatalFailureSurvivesOwnerRecreation() throws Exception {
        MemoryPrefs prefs = new MemoryPrefs(); Context.prefs = prefs;
        AppConfig.save(prefs.edit().putBoolean("enabled", true).putString("receiver", "invalid"));
        try { new EngineBackend(new Context()).start(() -> true); throw new AssertionError("Invalid receiver accepted"); }
        catch (IllegalArgumentException expected) { }
        require(!prefs.getString("manual_recovery_error", "").isEmpty(), "Fatal startup failure was not retained durably");
        new EngineBackend(new Context()).stop(); // Internal cleanup while saved ON must keep the gate.
        AppConfig.save(prefs.edit().putString("receiver", "192.0.2.1"));
        int before = commands;
        try { new EngineBackend(new Context()).start(() -> true); throw new AssertionError("Fatal startup retried after owner recreation"); }
        catch (IllegalStateException expected) { require(expected.getMessage().contains("requires OFF"), "Wrong recovery gate"); }
        require(commands == before, "Blocked fresh launch accessed ADB");
        AppConfig.accept(prefs, false);
        new EngineBackend(new Context()).stop();
        require(prefs.getString("manual_recovery_error", "").isEmpty(), "Confirmed explicit OFF did not allow recovery");
        AppConfig.accept(prefs, true);
        starting = true; markerCreated = false; staleStop = true;
        try { new EngineBackend(new Context()).start(() -> true); }
        finally { starting = false; }
        require(markerCreated, "Explicit recovery could not start audio");

        String run = "0123456789abcdef0123456789abcdef";
        AppConfig.save(prefs.edit().putString("active_run", run));
        health = true; healthAlive = true; nanos = 100000000000L;
        String prefix = "run=" + run + " uid=2000 pid=123 state=";
        healthStatus = prefix + "ERROR message=Capture permission denied";
        try { new EngineBackend(new Context()).check(); throw new AssertionError("Fatal engine error hidden"); }
        catch (IllegalStateException expected) { }
        require(!prefs.getString("manual_recovery_error", "").isEmpty(), "Fatal engine failure lost retry gate");
        healthAlive = false;
        try { new EngineBackend(new Context()).start(() -> true); throw new AssertionError("Dead fatal engine was recreated automatically"); }
        catch (IllegalStateException expected) { require(expected.getMessage().contains("requires OFF"), "Wrong fatal health gate"); }
        healthAlive = true;
        healthStatus = prefix + "IDLE v=2 frames_read=0 sent=0 dropped=0 observed_ns=99000000000 last_read_ns=0 last_send_ns=0 message=none";
        new EngineBackend(new Context(), () -> nanos).start(() -> true);
        require(prefs.getString("manual_recovery_error", "").isEmpty() && run.equals(prefs.getString("active_run", "")),
                "Healthy owned engine could not be validated and adopted without relaunch");
        health = false;
    }

    private static void missingNetworkDoesNotLaunch() throws Exception {
        MemoryPrefs prefs = new MemoryPrefs(); Context.prefs = prefs;
        AppConfig.save(prefs.edit().putString("receiver", "192.0.2.1"));
        int before = commands;
        try {
            new EngineBackend(new Context(), address -> false).start(() -> true);
            throw new AssertionError("Missing network launched engine");
        } catch (AudioController.NetworkUnavailableException expected) {
            require(before == commands && !prefs.contains("active_run") && !prefs.contains("original_surround"),
                    "Network must be checked before ADB, surround changes or launch");
        }
    }

    private static void unobservedTerminalFailureRequiresRecovery() throws Exception {
        String run = "0123456789abcdef0123456789abcdef";
        starting = true;
        try {
            for (String state : new String[] {"ERROR", "STOPPED"}) {
                for (boolean hasNetwork : new boolean[] {true, false}) {
                    MemoryPrefs prefs = new MemoryPrefs(); Context.prefs = prefs;
                    AppConfig.save(prefs.edit().putBoolean("enabled", true).putString("receiver", "192.0.2.1")
                            .putString("active_run", run));
                    startupPreviousStatus = "run=" + run + " uid=2000 pid=123 state=" + state
                            + " message=Capture permission denied";
                    markerCreated = false; startupLaunches = 0; startupSurroundWrites = 0;
                    try {
                        new EngineBackend(new Context(), address -> hasNetwork).start(() -> true);
                        throw new AssertionError("Unobserved " + state + " was relaunched without explicit recovery");
                    } catch (IllegalStateException expected) {
                        require(prefs.getString("manual_recovery_error", "").contains("Capture permission denied"),
                                "Unobserved terminal failure lost its reason, network=" + hasNetwork);
                    }
                    require(startupLaunches == 0 && startupSurroundWrites == 0 && !markerCreated
                            && run.equals(prefs.getString("active_run", "")), "Fatal owner was replaced or audio changed");
                }
            }
            // A dead foreign status and a recoverable network exit must still allow a new run.
            for (String status : new String[] {
                    "run=abcdef0123456789abcdef0123456789 uid=2000 pid=123 state=ERROR message=Old unrelated failure",
                    "run=" + run + " uid=2000 pid=123 state=NETWORK_UNAVAILABLE message=Network unavailable"}) {
                MemoryPrefs prefs = new MemoryPrefs(); Context.prefs = prefs;
                AppConfig.save(prefs.edit().putBoolean("enabled", true).putString("receiver", "192.0.2.1")
                        .putString("active_run", run));
                startupPreviousStatus = status; markerCreated = false; staleStop = true; startupLaunches = 0;
                new EngineBackend(new Context()).start(() -> true);
                require(startupLaunches == 1 && !run.equals(prefs.getString("active_run", ""))
                        && prefs.getString("manual_recovery_error", "").isEmpty(), "Recoverable restart was blocked");
            }
        } finally {
            starting = false; startupPreviousStatus = "";
        }
    }

    private static void startPreservesForeignStop() throws Exception {
        MemoryPrefs fresh = new MemoryPrefs();
        Context.prefs = fresh;
        AppConfig.save(fresh.edit().putString("receiver", "192.0.2.1"));
        starting = true;
        foreignOwner = true;
        staleStop = true;
        markerCreated = false;
        startupSurroundWrites = 0;
        try {
            try {
                new EngineBackend(new Context()).start(() -> true);
                throw new AssertionError("A foreign capture owner must block startup");
            } catch (IOException expected) {
                require(staleStop, "Starting erased an emergency Stop for another capture owner");
                require(!markerCreated, "Starting enabled a run while another owner held the lock");
                require(startupSurroundWrites == 0, "Starting changed another owner's audio setting");
            }
        } finally {
            starting = false;
            foreignOwner = false;
        }
    }

    private static void startClearsStaleStopBeforeEnabling() throws Exception {
        MemoryPrefs fresh = new MemoryPrefs();
        Context.prefs = fresh;
        AppConfig.save(fresh.edit().putString("receiver", "192.0.2.1"));
        starting = true;
        staleStop = true;
        markerCreated = false;
        try {
            new EngineBackend(new Context()).start(() -> true);
            require(markerCreated && !staleStop, "New run was not enabled");
        } finally {
            starting = false;
        }
    }

    private static void checkHeartbeat(MemoryPrefs prefs, String run) throws Exception {
        slow = false;
        health = true;
        nanos = 100000000000L;
        AppConfig.save(prefs.edit().putString("active_run", run));
        EngineBackend backend = new EngineBackend(new Context(), () -> nanos);
        String prefix = "run=" + run + " uid=2000 pid=123 state=IDLE ";
        String metrics = "v=2 frames_read=0 sent=0 dropped=0 observed_ns=";
        healthStatus = prefix + metrics + "99000000000 last_read_ns=0 last_send_ns=0 message=none";
        backend.check(); // A fresh idle heartbeat is healthy even without audio.
        String lastHealthy = healthStatus;
        healthStatus = prefix + metrics + "50000000000 last_read_ns=0 last_send_ns=0 message=none";
        MemoryPrefs adopted = new MemoryPrefs();
        AppConfig.save(adopted.edit().putString("receiver", "192.0.2.1").putString("active_run", run));
        Context.prefs = adopted;
        try {
            new EngineBackend(new Context(), () -> nanos).start(() -> true);
            throw new AssertionError("Owner restart adopted a stalled engine as ready ON");
        } catch (IllegalStateException expected) {
            require(expected.getMessage().contains("heartbeat"), "Wrong adoption failure: " + expected);
        } finally { Context.prefs = prefs; }
        try {
            backend.check();
            throw new AssertionError("Stale engine heartbeat was accepted as healthy");
        } catch (IllegalStateException expected) {
            require(expected.getMessage().contains("heartbeat"), "Wrong stale heartbeat error");
        }
        try {
            new EngineBackend(new Context(), address -> false).check();
            throw new AssertionError("Missing route hid a stale heartbeat");
        } catch (IllegalStateException expected) {
            require(expected.getMessage().contains("heartbeat"), "Stalled engine became retryable");
        }
        require(lastHealthy.equals(prefs.getString("engine_snapshot", "")),
                "A stale heartbeat must not replace the last checked snapshot");
        healthStatus = prefix + metrics + "99000000000 last_read_ns=0 last_send_ns=0 message=none";
        backend.check(); // A later heartbeat makes the same backend healthy again.
        healthStatus = prefix + "message=none";
        backend.check(); // Legacy snapshots have no monotonic timestamp to check.
        healthAlive = false;
        try {
            new EngineBackend(new Context(), address -> false).check();
            throw new AssertionError("Missing route hid a dead engine");
        } catch (IllegalStateException expected) {
            require(expected.getMessage().contains("stopped"), "Dead engine became retryable");
        } finally { healthAlive = true; }
        healthStatus = prefix.replace("state=IDLE", "state=NETWORK_UNAVAILABLE") + "message=Network unreachable";
        try { backend.check(); throw new AssertionError("Network error was not recoverable"); }
        catch (AudioController.NetworkUnavailableException expected) { }
        healthStatus = prefix.replace("state=IDLE", "state=ERROR") + "message=Capture permission denied";
        try {
            new EngineBackend(new Context(), address -> false).check();
            throw new AssertionError("Capture failure hidden by a missing network");
        } catch (IllegalStateException expected) {
            require(expected.getMessage().contains("permission"), "Wrong fatal error");
        }
        health = false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
