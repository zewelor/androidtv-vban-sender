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
        firstOffStopsAnUnknownPreexistingBackend();
        failedInitialOffNeedsAnExplicitRetry();
        firstOnAdoptsAnUnknownPreexistingBackend();
        offFencesDelayedStartAndLaterOnWaitsForCleanup();
        repeatedRequestsAreIdempotent();
        failedStopBlocksRestartUntilCleanupSucceeds();
        closeDoesNotPretendTheEngineStopped();
        System.out.println("AUDIO_CONTROLLER_PASS");
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
