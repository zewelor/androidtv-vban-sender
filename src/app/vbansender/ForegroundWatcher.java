package app.vbansender;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Runs on the box, outside the PCM loop; no laptop or receiver supervisor. */
final class ForegroundWatcher implements AutoCloseable {
    private volatile ForegroundPolicy policy;
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
        ForegroundPolicy current = policy;
        return !closed && failure == null && current != null
                && current.captureAllowed(System.nanoTime());
    }

    void checkFailure() throws Exception {
        if (failure != null) {
            throw new IOException("Foreground detection failed; capture stopped", failure);
        }
    }

    private void watch() {
        String previous = "not-observed";
        try {
            String home = resolveHome();
            policy = new ForegroundPolicy(home);
            System.out.println("HOME " + home);
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
                if (policy != null) policy.observe(null, System.nanoTime());
                System.err.println("DETECTION_ERROR: " + error);
            }
        }
    }

    private String resolveHome() throws Exception {
        Process current = new ProcessBuilder("cmd", "package", "resolve-activity", "--components",
                "--user", "current", "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.HOME").redirectErrorStream(true).start();
        process = current;
        try {
            if (!current.waitFor(2, TimeUnit.SECONDS)) {
                throw new IOException("Home detection timed out");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = current.getInputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    if (output.size() > 16384) throw new IOException("Home response exceeds 16 KiB");
                }
            }
            String component = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
            if (current.exitValue() != 0
                    || !component.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+")) {
                throw new IOException("Invalid Home response (exit " + current.exitValue() + "): " + component);
            }
            return component.substring(0, component.indexOf('/'));
        } finally {
            current.destroy();
            process = null;
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
