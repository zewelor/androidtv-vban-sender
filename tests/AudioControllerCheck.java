package app.vbansender.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Latch-controlled checks for serialized audio start/stop requests. */
public final class AudioControllerCheck {
    private static final long WAIT_SECONDS = 5;

    public static void main(String[] args) throws Exception {
        missingNetworkWaitsAfterConfirmedCleanup();
        networkRetryBackoffRecoveryAndOff();
        onlyStableHealthResetsNetworkBackoff();
        failedNetworkCleanupDoesNotRetry();
        adbRouteErrorRemainsFatal();
        networkWakeupDuringWaitPublicationIsRetained();
        runningBackendIsCheckedAndFailuresBecomeVisible();
        offDiscardsAnInFlightHealthError();
        firstOffStopsAnUnknownPreexistingBackend();
        failedInitialOffNeedsAnExplicitRetry();
        firstOnAdoptsAnUnknownPreexistingBackend();
        offFencesDelayedStartAndLaterOnWaitsForCleanup();
        repeatedRequestsAreIdempotent();
        failedStopBlocksRestartUntilCleanupSucceeds();
        closeDoesNotPretendTheEngineStopped();
        System.out.println("AUDIO_CONTROLLER_PASS");
    }

    private static void adbRouteErrorRemainsFatal() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        StateListener states = new StateListener();
        AudioController controller = new AudioController(new AudioController.Backend() {
            public void start(java.util.function.BooleanSupplier enabled) throws Exception {
                starts.incrementAndGet();
                throw new java.net.NoRouteToHostException("Local ADB connection failed");
            }
            public void stop() { }
            public void check() { }
        }, states);
        try {
            controller.request(true);
            await(states.firstError, "ADB route error was hidden");
            controller.retryNetwork();
            Thread.sleep(50);
            require(starts.get() == 1 && !states.hasState(AudioController.State.WAITING_NETWORK),
                    "An ADB I/O error enabled automatic receiver-network retry");
        } finally { controller.close(); }
    }

    private static void networkWakeupDuringWaitPublicationIsRetained() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<AudioController> owner = new AtomicReference<>();
        StateListener states = new StateListener();
        AudioController controller = new AudioController(new AudioController.Backend() {
            public void start(java.util.function.BooleanSupplier enabled) throws Exception {
                if (starts.incrementAndGet() == 1) throw new AudioController.NetworkUnavailableException("No route");
            }
            public void stop() { }
            public void check() { }
        }, new AudioController.Listener() {
            public void onState(AudioController.State state) { states.onState(state); }
            public void onError(Exception error) { states.onError(error); }
            public void onNetworkWait(long millis) { owner.get().retryNetwork(); }
        });
        owner.set(controller);
        try {
            controller.request(true);
            states.awaitState(AudioController.State.ON);
            require(starts.get() == 2 && states.errors.isEmpty(), "Network wakeup was lost while publishing wait");
        } finally { controller.close(); }
    }

    private static void networkRetryBackoffRecoveryAndOff() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1);
        AtomicInteger starts = new AtomicInteger(), stops = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean available = new java.util.concurrent.atomic.AtomicBoolean();
        List<Long> delays = Collections.synchronizedList(new ArrayList<Long>());
        CountDownLatch healthy = new CountDownLatch(1);
        StateListener states = new StateListener();
        AudioController controller = new AudioController(new AudioController.Backend() {
            public void start(java.util.function.BooleanSupplier enabled) throws Exception {
                starts.incrementAndGet();
                if (!available.get()) throw new AudioController.NetworkUnavailableException("Network unreachable");
            }
            public void stop() { stops.incrementAndGet(); }
            public void check() throws Exception {
                if (!available.get()) throw new AudioController.NetworkUnavailableException("Network lost");
                healthy.countDown();
            }
        }, new AudioController.Listener() {
            public void onState(AudioController.State state) { states.onState(state); }
            public void onError(Exception error) { states.onError(error); }
            public void onNetworkWait(long millis) { delays.add(millis); }
        }, null, clock::get);
        try {
            controller.request(true);
            long[] expected = {10000, 20000, 40000, 80000, 160000, 300000, 300000};
            for (int i = 0; i < expected.length; i++) {
                final int count = i + 1;
                awaitCondition(() -> delays.size() >= count, "network wait " + count);
                require(delays.get(i) == expected[i], "incorrect backoff " + delays);
                if (i + 1 < expected.length) controller.retryNetwork();
            }
            require(starts.get() == expected.length && stops.get() == starts.get(),
                    "each network attempt must finish cleanup before waiting");
            available.set(true);
            controller.retryNetwork(); // Same wakeup used by ConnectivityManager, without waiting 5 min.
            states.awaitState(AudioController.State.ON);
            clock.addAndGet(30000000000L);
            controller.check();
            await(healthy, "health check did not finish");
            available.set(false);
            controller.check();
            awaitCondition(() -> delays.size() == expected.length + 1, "loss while sending");
            require(delays.get(expected.length) == 10000, "healthy operation did not reset backoff");
            controller.request(false);
            states.awaitState(AudioController.State.OFF);
            int stoppedStarts = starts.get();
            controller.retryNetwork();
            Thread.sleep(50);
            require(starts.get() == stoppedStarts && states.errors.isEmpty(), "OFF allowed a retry or hid an error");
        } finally { controller.close(); }
    }

    private static void onlyStableHealthResetsNetworkBackoff() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1);
        java.util.concurrent.atomic.AtomicBoolean available = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean statusReadFails = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicInteger successfulChecks = new AtomicInteger();
        List<Long> delays = Collections.synchronizedList(new ArrayList<Long>());
        StateListener states = new StateListener();
        AudioController controller = new AudioController(new AudioController.Backend() {
            public void start(java.util.function.BooleanSupplier enabled) throws Exception {
                if (!available.get()) throw new AudioController.NetworkUnavailableException("No route");
            }
            public void stop() { }
            public void check() throws Exception {
                if (!available.get()) throw new AudioController.NetworkUnavailableException("Route lost");
                if (statusReadFails.get()) throw new java.io.IOException("Status read failed");
                successfulChecks.incrementAndGet();
            }
        }, new AudioController.Listener() {
            public void onState(AudioController.State state) { states.onState(state); }
            public void onError(Exception error) { states.onError(error); }
            public void onNetworkWait(long millis) { delays.add(millis); }
        }, null, clock::get);
        try {
            controller.request(true);
            awaitCondition(() -> delays.size() == 1, "initial network wait");
            long[] healthyWindows = {0, 29999999999L, 30000000000L};
            long[] expected = {20000, 40000, 10000};
            for (int i = 0; i < healthyWindows.length; i++) {
                available.set(true);
                controller.retryNetwork();
                states.awaitStateCount(AudioController.State.ON, i + 1);
                clock.addAndGet(healthyWindows[i]);
                controller.check();
                final int checks = i + 1;
                awaitCondition(() -> successfulChecks.get() == checks, "healthy check " + checks);
                available.set(false);
                controller.check();
                awaitCondition(() -> delays.size() == checks + 1, "network loss " + checks);
                require(delays.get(checks) == expected[i], "Wrong stability reset: " + delays);
            }
            // A failed status read interrupts the healthy window even if the process stays ON.
            available.set(true);
            controller.retryNetwork();
            states.awaitStateCount(AudioController.State.ON, 4);
            clock.addAndGet(20000000000L);
            statusReadFails.set(true);
            controller.check();
            await(states.firstError, "temporary health failure hidden");
            statusReadFails.set(false);
            clock.addAndGet(20000000000L);
            controller.check();
            awaitCondition(() -> successfulChecks.get() == 4, "health read recovery");
            available.set(false);
            controller.check();
            awaitCondition(() -> delays.size() == 5, "loss after interrupted health window");
            require(delays.get(4) == 20000, "Interrupted health window reset backoff too early: " + delays);
        } finally { controller.close(); }
    }

    private static void failedNetworkCleanupDoesNotRetry() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        StateListener listener = new StateListener();
        AudioController controller = new AudioController(new AudioController.Backend() {
            public void start(java.util.function.BooleanSupplier enabled) throws Exception {
                starts.incrementAndGet();
                throw new AudioController.NetworkUnavailableException("Network unreachable");
            }
            public void stop() throws Exception { throw new Exception("Cleanup failed"); }
            public void check() { }
        }, listener);
        try {
            controller.request(true);
            await(listener.firstError, "cleanup failure hidden");
            controller.retryNetwork();
            Thread.sleep(50);
            require(starts.get() == 1 && !listener.hasState(AudioController.State.WAITING_NETWORK),
                    "failed cleanup must block automatic retries");
        } finally { controller.close(); }
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition, String message)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        require(condition.getAsBoolean(), message);
    }

    private static void missingNetworkWaitsAfterConfirmedCleanup() throws Exception {
        AtomicInteger stops = new AtomicInteger();
        CountDownLatch waiting = new CountDownLatch(1);
        List<Exception> errors = Collections.synchronizedList(new ArrayList<Exception>());
        AudioController controller = new AudioController(new AudioController.Backend() {
            public void start(java.util.function.BooleanSupplier enabled) throws Exception {
                throw new AudioController.NetworkUnavailableException("Network unreachable");
            }
            public void stop() { stops.incrementAndGet(); }
            public void check() { }
        }, new AudioController.Listener() {
            public void onState(AudioController.State state) {
                if ("WAITING_NETWORK".equals(state.name())) waiting.countDown();
            }
            public void onError(Exception error) { errors.add(error); }
        });
        try {
            controller.request(true);
            await(waiting, "missing network did not enter a recoverable waiting state");
            require(stops.get() == 1 && errors.isEmpty(),
                    "network wait must follow confirmed cleanup without a fatal error");
        } finally { controller.close(); }
    }

    private static void runningBackendIsCheckedAndFailuresBecomeVisible() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger checks = new AtomicInteger();
        StateListener listener = new StateListener();
        AudioController controller = new AudioController(new AudioController.Backend() {
            public void start(java.util.function.BooleanSupplier enabled) { starts.incrementAndGet(); }
            public void stop() { }
            public void check() throws Exception {
                if (checks.incrementAndGet() == 1) throw new Exception("Engine stopped");
            }
        }, listener);
        try {
            controller.request(true);
            listener.awaitState(AudioController.State.ON);
            controller.request(true);
            await(listener.firstError, "repeated ON did not detect a stopped engine");
            controller.request(true);
            listener.awaitStateCount(AudioController.State.ON, 2);
            require(starts.get() == 1 && checks.get() == 2,
                    "health checks must report recovery without creating duplicate engines");
        } finally { controller.close(); }
    }

    private static void offDiscardsAnInFlightHealthError() throws Exception {
        CountDownLatch checking = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StateListener listener = new StateListener();
        AudioController controller = new AudioController(new AudioController.Backend() {
            public void start(java.util.function.BooleanSupplier enabled) { }
            public void stop() { }
            public void check() throws Exception {
                checking.countDown();
                await(release, "health check was not released");
                throw new Exception("Stale error");
            }
        }, listener);
        try {
            controller.request(true);
            listener.awaitState(AudioController.State.ON);
            controller.check();
            await(checking, "periodic health check did not start");
            controller.request(false);
            release.countDown();
            listener.awaitState(AudioController.State.OFF);
            require(listener.errors.isEmpty(), "completed OFF must discard an obsolete health error");
        } finally { release.countDown(); controller.close(); }
    }

    private static void firstOffStopsAnUnknownPreexistingBackend() throws Exception {
        PreexistingBackend backend = new PreexistingBackend();
        StateListener listener = new StateListener();
        AudioController controller = new AudioController(backend, listener);
        try {
            controller.request(false);
            await(backend.stopEntered, "first OFF did not check and stop the preexisting engine");
            require(backend.active,
                    "the preexisting engine must remain active until cleanup Stop completes");
            require(!listener.hasState(AudioController.State.OFF),
                    "OFF must not be reported before cleanup Stop confirms it");

            backend.releaseStop.countDown();
            listener.awaitState(AudioController.State.OFF);
            require(!backend.active && backend.stops.get() == 1,
                    "first OFF must leave the preexisting engine stopped exactly once");
        } finally {
            backend.releaseStop.countDown();
            controller.close();
        }
    }

    private static void firstOnAdoptsAnUnknownPreexistingBackend() throws Exception {
        PreexistingBackend backend = new PreexistingBackend();
        StateListener listener = new StateListener();
        AudioController controller = new AudioController(backend, listener);
        try {
            controller.request(true);
            await(backend.startEntered, "first ON did not ask the backend to verify/adopt the engine");
            listener.awaitState(AudioController.State.ON);
            require(backend.active && backend.createdEngines.get() == 0,
                    "first ON must adopt an already-running engine without creating a duplicate");
            controller.request(true);
            require(backend.startCalls.get() == 1,
                    "repeated ON must not issue another backend adoption while already ON");
        } finally {
            controller.close();
        }
    }

    private static void failedInitialOffNeedsAnExplicitRetry() throws Exception {
        InitialFailStopBackend backend = new InitialFailStopBackend();
        StateListener listener = new StateListener();
        AudioController controller = new AudioController(backend, listener);
        try {
            controller.request(false);
            await(backend.stopEntered, "initial OFF did not attempt cleanup");
            backend.releaseStop.countDown();
            await(listener.firstError, "initial Stop failure was not reported");
            require(!listener.hasState(AudioController.State.OFF),
                    "failed initial Stop must not report OFF");
            require(!backend.secondStopSucceeded.await(250, TimeUnit.MILLISECONDS),
                    "failed initial Stop must not trigger an automatic retry loop");
            require(backend.stops.get() == 1,
                    "the failed initial Stop must be attempted only once automatically");

            controller.request(false);
            await(backend.secondStopSucceeded, "a later OFF request did not retry initial cleanup");
            listener.awaitState(AudioController.State.OFF);
            require(!backend.active && backend.stops.get() == 2,
                    "explicit OFF retry must confirm and establish the stopped state");
        } finally {
            backend.releaseStop.countDown();
            controller.close();
        }
    }

    private static void offFencesDelayedStartAndLaterOnWaitsForCleanup() throws Exception {
        RaceBackend backend = new RaceBackend();
        StateListener listener = new StateListener();
        AudioController controller = new AudioController(backend, listener);
        try {
            controller.request(true);
            await(backend.firstStartEntered, "first start did not begin");
            controller.request(false);
            controller.request(true);
            backend.releaseFirstStart.countDown();

            await(backend.firstStopEntered, "stale start was not cleaned up");
            require(Boolean.FALSE.equals(backend.firstStartAllowed.get()),
                    "an OFF request must fence the in-flight start even after a later ON");
            require(backend.starts.get() == 1,
                    "a later ON must wait until stale-start cleanup completes");
            require(!listener.hasState(AudioController.State.OFF),
                    "controller reported OFF before the cleanup Stop completed");

            backend.releaseFirstStop.countDown();
            await(backend.secondStartEntered, "latest ON was not started after cleanup");
            listener.awaitState(AudioController.State.ON);
            require(backend.events.indexOf("stop-exit-1") < backend.events.indexOf("start-2"),
                    "the second start overlapped or preceded cleanup Stop");
            require(backend.starts.get() == 2 && backend.stops.get() == 1,
                    "one stale start should produce one cleanup and one current start");
            require(backend.maxConcurrent.get() == 1,
                    "backend start and Stop calls must never overlap");
        } finally {
            backend.releaseFirstStart.countDown();
            backend.releaseFirstStop.countDown();
            controller.close();
        }
    }

    private static void repeatedRequestsAreIdempotent() throws Exception {
        CountingBackend backend = new CountingBackend();
        StateListener listener = new StateListener();
        AudioController controller = new AudioController(backend, listener);
        try {
            controller.request(true);
            listener.awaitState(AudioController.State.ON);
            controller.request(true);
            controller.request(false);
            listener.awaitState(AudioController.State.OFF);
            controller.request(false);
            controller.request(true);
            listener.awaitStateCount(AudioController.State.ON, 2);
            require(backend.starts.get() == 2,
                    "repeated ON must not start a second engine while already ON");
            require(backend.stops.get() == 1,
                    "repeated OFF must not stop an already stopped engine again");
        } finally {
            controller.close();
        }
    }

    private static void failedStopBlocksRestartUntilCleanupSucceeds() throws Exception {
        FailingStopBackend backend = new FailingStopBackend();
        StateListener listener = new StateListener();
        AudioController controller = new AudioController(backend, listener);
        try {
            controller.request(true);
            listener.awaitState(AudioController.State.ON);
            controller.request(false);
            await(backend.firstStopEntered, "first stop did not begin");
            controller.request(true);
            backend.releaseFirstStop.countDown();
            await(listener.firstError, "failed stop was not reported to the listener");
            require(listener.hasState(AudioController.State.STOPPING),
                    "failed Stop must remain visibly in STOPPING");
            require(!listener.hasState(AudioController.State.OFF),
                    "failed Stop must not report OFF");
            require(!backend.secondStartEntered.await(250, TimeUnit.MILLISECONDS),
                    "controller started another engine while cleanup Stop was failing");

            controller.request(false);
            await(backend.secondStopSucceeded, "a later OFF request did not retry cleanup");
            listener.awaitState(AudioController.State.OFF);
            controller.request(true);
            await(backend.secondStartEntered, "ON did not start after cleanup succeeded");
            listener.awaitStateCount(AudioController.State.ON, 2);
            require(backend.starts.get() == 2,
                    "the engine must start only after a successful cleanup Stop");
        } finally {
            backend.releaseFirstStop.countDown();
            controller.close();
        }
    }

    private static void closeDoesNotPretendTheEngineStopped() throws Exception {
        CountingBackend backend = new CountingBackend();
        StateListener listener = new StateListener();
        AudioController controller = new AudioController(backend, listener);
        controller.request(true);
        listener.awaitState(AudioController.State.ON);
        controller.close();
        require(backend.stops.get() == 0,
                "close must not issue a Stop in place of the service's cleanup");
        require(!listener.hasState(AudioController.State.OFF),
                "close must not claim the engine is OFF");
    }

    private static void await(CountDownLatch latch, String message) throws Exception {
        require(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class StateListener implements AudioController.Listener {
        private final List<AudioController.State> states = new ArrayList<>();
        private final List<Exception> errors = new ArrayList<>();
        private final CountDownLatch firstError = new CountDownLatch(1);

        @Override
        public synchronized void onState(AudioController.State state) {
            states.add(state);
            notifyAll();
        }

        @Override
        public synchronized void onError(Exception error) {
            errors.add(error);
            firstError.countDown();
            notifyAll();
        }

        synchronized boolean hasState(AudioController.State expected) {
            return states.contains(expected);
        }

        synchronized void awaitState(AudioController.State expected) throws InterruptedException {
            awaitStateCount(expected, 1);
        }

        synchronized void awaitStateCount(AudioController.State expected, int count)
                throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (count(states, expected) < count) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AssertionError("timed out waiting for " + expected + " x" + count
                            + "; states=" + states + ", errors=" + errors);
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }

        private static int count(List<AudioController.State> states, AudioController.State expected) {
            int result = 0;
            for (AudioController.State state : states) {
                if (state == expected) {
                    result++;
                }
            }
            return result;
        }
    }

    private static final class RaceBackend implements AudioController.Backend {
        @Override
        public void check() { }
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        final CountDownLatch firstStartEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirstStart = new CountDownLatch(1);
        final CountDownLatch firstStopEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirstStop = new CountDownLatch(1);
        final CountDownLatch secondStartEntered = new CountDownLatch(1);
        final AtomicReference<Boolean> firstStartAllowed = new AtomicReference<>();
        final AtomicInteger activeOperations = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public void start(java.util.function.BooleanSupplier stillEnabled) throws Exception {
            beginOperation();
            try {
                int call = starts.incrementAndGet();
                events.add("start-" + call);
                if (call == 1) {
                    firstStartEntered.countDown();
                    await(releaseFirstStart, "first start release timed out");
                }
                boolean allowed = stillEnabled.getAsBoolean();
                if (call == 1) {
                    firstStartAllowed.set(allowed);
                }
                if (allowed && call == 2) {
                    secondStartEntered.countDown();
                }
            } finally {
                activeOperations.decrementAndGet();
            }
        }

        @Override
        public void stop() throws Exception {
            beginOperation();
            try {
                int call = stops.incrementAndGet();
                events.add("stop-enter-" + call);
                if (call == 1) {
                    firstStopEntered.countDown();
                    await(releaseFirstStop, "first stop release timed out");
                }
                events.add("stop-exit-" + call);
            } finally {
                activeOperations.decrementAndGet();
            }
        }

        private void beginOperation() {
            int active = activeOperations.incrementAndGet();
            while (true) {
                int previous = maxConcurrent.get();
                if (active <= previous || maxConcurrent.compareAndSet(previous, active)) {
                    return;
                }
            }
        }
    }

    private static class PreexistingBackend implements AudioController.Backend {
        @Override
        public void check() { }
        volatile boolean active = true;
        final AtomicInteger startCalls = new AtomicInteger();
        final AtomicInteger createdEngines = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        final CountDownLatch startEntered = new CountDownLatch(1);
        final CountDownLatch stopEntered = new CountDownLatch(1);
        final CountDownLatch releaseStop = new CountDownLatch(1);

        @Override
        public void start(java.util.function.BooleanSupplier stillEnabled) throws Exception {
            startCalls.incrementAndGet();
            startEntered.countDown();
            if (stillEnabled.getAsBoolean() && !active) {
                active = true;
                createdEngines.incrementAndGet();
            }
        }

        @Override
        public void stop() throws Exception {
            stops.incrementAndGet();
            stopEntered.countDown();
            await(releaseStop, "preexisting backend Stop release timed out");
            active = false;
        }
    }

    private static final class InitialFailStopBackend extends PreexistingBackend {
        final CountDownLatch secondStopSucceeded = new CountDownLatch(1);

        @Override
        public void stop() throws Exception {
            int call = stops.incrementAndGet();
            if (call == 1) {
                stopEntered.countDown();
                await(releaseStop, "initial failing Stop release timed out");
                throw new Exception("synthetic initial Stop failure");
            }
            active = false;
            secondStopSucceeded.countDown();
        }
    }

    private static class CountingBackend implements AudioController.Backend {
        @Override
        public void check() { }
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();

        @Override
        public void start(java.util.function.BooleanSupplier stillEnabled) throws Exception {
            if (stillEnabled.getAsBoolean()) {
                starts.incrementAndGet();
            }
        }

        @Override
        public void stop() throws Exception {
            stops.incrementAndGet();
        }
    }

    private static final class FailingStopBackend extends CountingBackend {
        final CountDownLatch firstStopEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirstStop = new CountDownLatch(1);
        final CountDownLatch secondStopSucceeded = new CountDownLatch(1);
        final CountDownLatch secondStartEntered = new CountDownLatch(1);

        @Override
        public void start(java.util.function.BooleanSupplier stillEnabled) {
            if (stillEnabled.getAsBoolean()) {
                int call = starts.incrementAndGet();
                if (call == 2) {
                    secondStartEntered.countDown();
                }
            }
        }

        @Override
        public void stop() throws Exception {
            int call = stops.incrementAndGet();
            if (call == 1) {
                firstStopEntered.countDown();
                await(releaseFirstStop, "first stop release timed out");
                throw new Exception("synthetic first Stop failure");
            }
            secondStopSucceeded.countDown();
        }
    }
}
