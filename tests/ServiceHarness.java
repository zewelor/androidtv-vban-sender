package app.vbansender.app;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import java.lang.reflect.Field;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Production dispatch and service callbacks against deterministic lifecycle adapters. */
public final class ServiceHarness {
    private final MemoryPrefs prefs = new MemoryPrefs();
    private final Context ui = new Context();
    private final Backend backend = new Backend();
    private final VbanService service = new VbanService();
    private int command;
    private int startId;

    private ServiceHarness() {
        Context.prefs = prefs;
        Context.commands.clear();
        Handler.queue.clear();
        EngineBackend.delegate = backend;
        service.onCreate();
    }

    public static void main(String[] args) throws Exception {
        ServiceHarness h = new ServiceHarness();
        try {
            switch (args[0]) {
                case "delayed_commands": h.delayedCommands(); break;
                case "failed_stop": h.failedStop(); break;
                case "stale_off": h.staleOff(); break;
                case "shutdown_rejected": h.shutdownRejected(); break;
                case "off_on_during_check": h.offOnDuringCheck(); break;
                case "replacement_during_start": h.replacementDuringStart(); break;
                case "replacement_with_pending_off": h.replacementWithPendingOff(); break;
                case "replacement_cleanup_fails": h.replacementCleanupFails(); break;
                default: throw new AssertionError("Unknown scenario");
            }
            System.out.println(args[0] + " PASS");
        } finally { h.backend.releaseStart.countDown(); h.backend.releaseCheck.countDown(); h.service.onDestroy(); }
    }

    private void choose(boolean enabled) {
        VbanService.setEnabled(ui, enabled);
    }

    private void deliver() {
        service.serverStart = ++startId;
        service.onStartCommand(Context.commands.get(command++), 0, startId);
    }

    private void reconcileSaved(boolean enabled) {
        AppConfig.save(prefs.edit().putBoolean("enabled", enabled));
        ui.startForegroundService(new Intent(ui, VbanService.class));
        deliver();
    }

    private void delayedCommands() throws Exception {
        backend.blockStart = true;
        reconcileSaved(true);
        await(() -> backend.starts.get() == 1, "first start");
        choose(false); choose(true);
        deliver(); deliver();
        backend.releaseStart.countDown();
        await(() -> backend.starts.get() == 2, "OFF was lost during delayed command dispatch");
        await(() -> "ON".equals(prefs.getString("controller_state", "")), "restart ON");
        require(backend.stops.get() == 1 && backend.active.get(), "restart did not follow cleanup");
    }

    private void failedStop() throws Exception {
        reconcileSaved(true);
        await(() -> "ON".equals(prefs.getString("controller_state", "")), "initial ON");
        backend.failStop.set(true);
        choose(false); deliver();
        await(() -> !prefs.getString("last_error", "").isEmpty(), "Stop error");
        choose(true);
        require("STOPPING".equals(prefs.getString("controller_state", ""))
                && !prefs.getString("last_error", "").isEmpty(), "UI erased failed cleanup and published a fake STARTING");
        deliver();
        require(backend.starts.get() == 1, "ON bypassed failed cleanup");
        choose(false); deliver();
        await(() -> "OFF".equals(prefs.getString("controller_state", "")), "cleanup retry");
        require(!backend.active.get() && prefs.getString("last_error", "").isEmpty(), "cleanup did not restore OFF");
    }

    private void staleOff() throws Exception {
        reconcileSaved(false);
        await(() -> "OFF".equals(prefs.getString("controller_state", "")) && Handler.queue.size() >= 2, "old OFF event");
        choose(true); deliver();
        await(() -> "ON".equals(prefs.getString("controller_state", "")), "new ON");
        Field field = VbanService.class.getDeclaredField("controller"); field.setAccessible(true);
        backend.blockCheck = true;
        ((AudioController) field.get(service)).check();
        require(backend.checking.await(3, TimeUnit.SECONDS), "check did not start");
        choose(false); deliver();
        drain();
        require(!service.stopped && service.foreground, "stale OFF closed the service with cleanup pending");
        backend.releaseCheck.countDown();
        await(() -> "OFF".equals(prefs.getString("controller_state", "")), "pending OFF cleanup");
        await(() -> !Handler.queue.isEmpty(), "current OFF event");
        drain();
        require(!backend.active.get() && service.stopped, "current OFF was not accepted");
    }

    private void shutdownRejected() throws Exception {
        reconcileSaved(false);
        await(() -> "OFF".equals(prefs.getString("controller_state", "")) && Handler.queue.size() >= 2, "confirmed OFF");
        service.serverStart = 2; // Android knows about a newer start before delivering it.
        drain();
        require(!service.stopped && service.foreground, "foreground removed after rejected shutdown");
        choose(false); deliver();
        drain();
        require(service.stopped && !service.foreground, "repeated confirmed OFF did not finish shutdown");
    }

    private AudioController controller() throws Exception {
        Field field = VbanService.class.getDeclaredField("controller"); field.setAccessible(true);
        return (AudioController) field.get(service);
    }

    private void offOnDuringCheck() throws Exception {
        reconcileSaved(true);
        await(() -> "ON".equals(prefs.getString("controller_state", "")), "initial ON");
        backend.blockCheck = true;
        controller().check();
        require(backend.checking.await(3, TimeUnit.SECONDS), "blocked health check");
        choose(false); choose(true); deliver(); deliver();
        backend.releaseCheck.countDown();
        await(() -> backend.starts.get() == 2, "delivered OFF was lost while checking an ON engine");
        require(backend.stops.get() == 1 && backend.events.indexOf("stop") < backend.events.indexOf("start-2"),
                "fresh ON did not follow cleanup");
    }

    private VbanService replacement() {
        service.onDestroy();
        VbanService replacement = new VbanService(); replacement.onCreate();
        replacement.serverStart = ++startId;
        replacement.onStartCommand(new Intent(ui, VbanService.class), 0, startId);
        return replacement;
    }

    private void replacementDuringStart() throws Exception {
        backend.blockStart = true;
        reconcileSaved(true);
        await(() -> backend.active.get(), "engine created by pending Start");
        VbanService replacement = replacement();
        try {
            require(!backend.secondStart.await(200, TimeUnit.MILLISECONDS), "replacement overlapped the old worker");
            backend.releaseStart.countDown();
            await(() -> backend.starts.get() == 2 && "ON".equals(prefs.getString("controller_state", "")), "replacement ON");
            require(backend.active.get() && backend.stops.get() == 1
                    && backend.events.indexOf("stop") < backend.events.indexOf("start-2"), "old cleanup stopped the replacement engine");
            require(!prefs.states.contains("STOPPING") && !prefs.states.contains("OFF"), "destroyed service published stale progress");
        } finally { backend.releaseStart.countDown(); replacement.onDestroy(); }
    }

    private void replacementWithPendingOff() throws Exception {
        reconcileSaved(true);
        await(() -> "ON".equals(prefs.getString("controller_state", "")), "initial ON");
        backend.blockCheck = true;
        controller().check();
        require(backend.checking.await(3, TimeUnit.SECONDS), "blocked health check");
        choose(false); choose(true); deliver(); deliver();
        VbanService replacement = replacement();
        try {
            require(!backend.secondStart.await(200, TimeUnit.MILLISECONDS), "replacement overlapped the old check");
            backend.releaseCheck.countDown();
            await(() -> backend.starts.get() == 2 && "ON".equals(prefs.getString("controller_state", "")), "replacement ON");
            require(backend.stops.get() == 1 && backend.events.indexOf("stop") < backend.events.indexOf("start-2"),
                    "replacement discarded the pending OFF fence");
        } finally { backend.releaseCheck.countDown(); replacement.onDestroy(); }
    }

    private void replacementCleanupFails() throws Exception {
        backend.blockStart = true;
        reconcileSaved(true);
        await(() -> backend.active.get(), "old Start");
        backend.failStop.set(true);
        VbanService replacement = replacement();
        try {
            backend.releaseStart.countDown();
            await(() -> backend.stops.get() == 2 && "OFF".equals(prefs.getString("controller_state", "")), "replacement cleanup");
            require(backend.starts.get() == 1 && !backend.active.get()
                    && !prefs.getString("last_error", "").isEmpty(), "failed cleanup was silently adopted as ON");
            choose(false);
            replacement.serverStart = ++startId;
            replacement.onStartCommand(Context.commands.get(command++), 0, startId);
            require(replacement.stopped && prefs.getString("last_error", "").isEmpty(), "confirmed OFF retained an obsolete error");
        } finally { backend.releaseStart.countDown(); replacement.onDestroy(); }
        choose(true);
        VbanService retry = new VbanService(); retry.onCreate();
        try {
            retry.serverStart = ++startId;
            retry.onStartCommand(Context.commands.get(command++), 0, startId);
            await(() -> backend.starts.get() == 2 && "ON".equals(prefs.getString("controller_state", "")), "explicit retry");
            require(backend.active.get() && prefs.getString("last_error", "").isEmpty(), "retry did not establish ON");
        } finally { retry.onDestroy(); }
    }

    private static void drain() {
        Runnable task;
        while ((task = Handler.queue.poll()) != null) task.run();
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long end = System.nanoTime() + 3000000000L;
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        require(condition.getAsBoolean(), message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Backend implements AudioController.Backend {
        final AtomicInteger starts = new AtomicInteger(), stops = new AtomicInteger();
        final AtomicBoolean active = new AtomicBoolean(), failStop = new AtomicBoolean();
        final AtomicBoolean cleanupPending = new AtomicBoolean();
        final CountDownLatch releaseStart = new CountDownLatch(1), releaseCheck = new CountDownLatch(1), checking = new CountDownLatch(1);
        volatile boolean blockStart, blockCheck;
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch secondStart = new CountDownLatch(1);
        public void start(BooleanSupplier enabled) throws Exception {
            if (cleanupPending.get()) throw new IllegalStateException("Previous cleanup is incomplete");
            int start = starts.incrementAndGet();
            events.add("start-" + start);
            active.set(true);
            if (start == 2) secondStart.countDown();
            if (start == 1 && blockStart) require(releaseStart.await(3, TimeUnit.SECONDS), "start release");
        }
        public void stop() throws Exception {
            cleanupPending.set(true);
            events.add("stop");
            stops.incrementAndGet();
            if (failStop.getAndSet(false)) throw new Exception("Stop was not confirmed");
            active.set(false);
            cleanupPending.set(false);
        }
        public void check() throws Exception {
            checking.countDown();
            if (blockCheck) require(releaseCheck.await(3, TimeUnit.SECONDS), "check release");
        }
    }

}
