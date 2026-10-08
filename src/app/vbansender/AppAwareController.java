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
        while (enabled.getAsBoolean()) {
            watcher.checkFailure();
            if (keepRunning.getAsBoolean()) {
                if (!"STARTING_CAPTURE".equals(state)) {
                    status.update("STARTING_CAPTURE");
                    state = "STARTING_CAPTURE";
                }
                capture.run(keepRunning);
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
