package app.vbansender;

import java.util.function.BooleanSupplier;

/** One owner serializes capture sessions; Stop and the finite deadline always win. */
final class AppAwareController {
    interface Capture {
        void run(BooleanSupplier keepRunning) throws Exception;
    }

    interface Status {
        void update(String state) throws Exception;
    }

    static void run(ForegroundWatcher watcher, Capture capture, BooleanSupplier enabled,
            long deadline) throws Exception {
        runContinuously(watcher, capture, new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return enabled.getAsBoolean() && System.nanoTime() < deadline;
            }
        }, new Status() {
            @Override
            public void update(String state) {
                // Finite developer probes report through the capture backend.
            }
        });
    }

    static void runContinuously(ForegroundWatcher watcher, Capture capture,
            BooleanSupplier enabled, Status status) throws Exception {
        BooleanSupplier keepRunning = new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return enabled.getAsBoolean() && watcher.captureAllowed();
            }
        };
        String state = null;
        int captureFailures = 0;
        long lastCaptureFailure = 0;
        while (enabled.getAsBoolean()) {
            watcher.checkFailure();
            if (keepRunning.getAsBoolean()) {
                if (!"STARTING_CAPTURE".equals(state)) {
                    status.update("STARTING_CAPTURE");
                    state = "STARTING_CAPTURE";
                }
                try {
                    capture.run(keepRunning);
                    captureFailures = 0;
                } catch (CaptureRestartException unavailable) {
                    long now = System.nanoTime();
                    if (now - lastCaptureFailure > 10000000000L) captureFailures = 0;
                    lastCaptureFailure = now;
                    if (++captureFailures > 3) throw unavailable;
                    status.update("IDLE");
                    state = "IDLE";
                    System.err.println("CAPTURE_WAIT: " + unavailable.getMessage());
                    long retryAt = System.nanoTime() + 1000000000L;
                    while (keepRunning.getAsBoolean() && System.nanoTime() < retryAt) {
                        Thread.sleep(20);
                    }
                }
                if (enabled.getAsBoolean() && !"IDLE".equals(state)) {
                    status.update("IDLE");
                    state = "IDLE";
                }
            } else {
                if (!"IDLE".equals(state)) {
                    status.update("IDLE");
                    state = "IDLE";
                }
                Thread.sleep(20);
            }
        }
        watcher.checkFailure();
    }
}
