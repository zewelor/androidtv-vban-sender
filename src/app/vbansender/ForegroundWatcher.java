package app.vbansender;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Runs on the box, outside the PCM loop; no laptop or receiver supervisor. */
final class ForegroundWatcher implements AutoCloseable {
    private final ForegroundPolicy policy = new ForegroundPolicy();
    private final Thread thread = new Thread(new Runnable() {
        @Override
        public void run() {
            watch();
        }
    }, "foreground-watcher");
    private volatile boolean closed;
    private volatile Exception failure;
    private volatile Process process;

    void start() {
        thread.start();
    }

    boolean captureAllowed() {
        return !closed && failure == null && policy.captureAllowed(System.nanoTime());
    }

    void checkFailure() throws Exception {
        if (failure != null) {
            throw new IOException("Foreground detection failed; capture stopped", failure);
        }
    }

    private void watch() {
        String previous = "not-observed";
        try {
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            while (!closed) {
                output.reset();
                Process current = new ProcessBuilder("dumpsys", "-t", "1", "activity", "activities")
                        .redirectErrorStream(true).start();
                process = current;
                String dump;
                try (InputStream input = current.getInputStream()) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        if (output.size() > 1048576) {
                            throw new IOException("Foreground dump exceeds 1 MiB");
                        }
                    }
                    int code = current.waitFor();
                    dump = new String(output.toByteArray(), StandardCharsets.UTF_8);
                    if (dump.contains("DUMP TIMEOUT") && !dump.contains("Permission Denial")) {
                        // Release capture immediately; a busy activity service can recover.
                        policy.observe(null, System.nanoTime());
                        if (!"timeout".equals(previous)) {
                            System.err.println("DETECTION_WAIT: dumpsys timed out; retrying");
                            previous = "timeout";
                        }
                        Thread.sleep(250);
                        continue;
                    }
                    if (code != 0 || dump.contains("Permission Denial")
                            || dump.contains("Can't find service")) {
                        throw new IOException("Invalid foreground dump (exit " + code + "): " + dump);
                    }
                } finally {
                    current.destroy();
                    process = null;
                }
                String application = ForegroundPolicy.parse(dump);
                long now = System.nanoTime();
                policy.observe(application, now);
                String display = application == null ? "unknown" : application;
                if (!display.equals(previous)) {
                    System.out.println("FOREGROUND " + display + " monotonic_ns=" + now);
                    previous = display;
                }
                Thread.sleep(250);
            }
        } catch (Exception error) {
            if (!closed) {
                failure = error;
                policy.observe(null, System.nanoTime());
                System.err.println("DETECTION_ERROR: " + error);
            }
        }
    }

    @Override
    public void close() throws InterruptedException {
        closed = true;
        Process current = process;
        if (current != null) {
            current.destroy();
        }
        thread.interrupt();
        thread.join(2000);
        if (thread.isAlive()) {
            throw new IllegalStateException("Foreground watcher did not stop");
        }
    }
}
