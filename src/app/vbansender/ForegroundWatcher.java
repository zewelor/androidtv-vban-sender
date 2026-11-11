package app.vbansender;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs on the box, outside the PCM loop; no laptop or receiver supervisor. */
final class ForegroundWatcher implements AutoCloseable {
    private static final long DUMP_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2);
    private final ExecutorService dumpReader = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "foreground-dump-reader");
            thread.setDaemon(true);
            return thread;
        }
    });
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
            while (!closed) {
                Process current = new ProcessBuilder("dumpsys", "-t", "1", "activity", "activities")
                        .redirectErrorStream(true).start();
                process = current;
                String dump;
                try {
                    dump = readDump(current);
                    int code = current.exitValue();
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
                    try {
                        if (current.isAlive()
                                && !current.destroyForcibly().waitFor(1, TimeUnit.SECONDS)) {
                            throw new IOException("Foreground dump process did not stop");
                        }
                    } finally {
                        process = null;
                    }
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
        } finally {
            dumpReader.shutdownNow();
        }
    }

    private String readDump(Process current) throws Exception {
        long deadline = System.nanoTime() + DUMP_TIMEOUT_NANOS;
        Future<String> result = dumpReader.submit(new Callable<String>() {
            @Override public String call() throws Exception {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                try (InputStream input = current.getInputStream()) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        if (output.size() > 1048576) {
                            throw new IOException("Foreground dump exceeds 1 MiB");
                        }
                    }
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        });
        try {
            String dump = result.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            long remaining = deadline - System.nanoTime();
            if (!current.waitFor(Math.max(0, remaining), TimeUnit.NANOSECONDS)) {
                throw new TimeoutException();
            }
            return dump;
        } catch (TimeoutException timeout) {
            throw new IOException("Foreground dump timed out", timeout);
        } catch (ExecutionException error) {
            throw new IOException("Cannot read foreground dump", error.getCause());
        } finally {
            result.cancel(true);
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
