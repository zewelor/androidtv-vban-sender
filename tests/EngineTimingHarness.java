package app.vbansender;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;

/** Real marker cancellation and status IPC, with bounded filesystem polling. */
public final class EngineTimingHarness {
    private static final long POLL_NANOS = 500000000L;
    private static final long POLL_OBSERVATION_NANOS = 250000000L;
    private static final long CALL_INTERVAL_NANOS = 1000000L;
    private static final long CANCELLATION_TIMEOUT_NANOS = 1000000000L;

    public static void main(String[] args) throws Exception {
        File directory = new File(args[1]);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create test directory: " + directory);
        }
        if ("check".equals(args[0])) {
            boundedPolling(directory);
            cancellationIsFinal(directory, false);
            cancellationIsFinal(directory, true);
            System.out.println("ENGINE_TIMING_PASS");
        } else if ("benchmark".equals(args[0])) {
            benchmark(directory);
        } else {
            throw new IllegalArgumentException("Expected check or benchmark");
        }
    }

    private static void boundedPolling(File directory) throws Exception {
        CountingFile marker = new CountingFile(directory, "enabled-count");
        CountingFile stop = new CountingFile(directory, "stop-count");
        Files.write(marker.toPath(), new byte[0]);
        Files.deleteIfExists(stop.toPath());
        EngineRunGate gate = new EngineRunGate(marker, stop);
        require(gate.prepare(), "Valid marker did not prepare");
        marker.probes = 0;
        stop.probes = 0;
        long started = System.nanoTime();
        long nextCall = started;
        do {
            require(gate.enabled(), "Valid run canceled during polling");
            nextCall += CALL_INTERVAL_NANOS;
            LockSupport.parkNanos(Math.max(0, nextCall - System.nanoTime()));
        } while (System.nanoTime() - started < POLL_OBSERVATION_NANOS);
        long elapsed = System.nanoTime() - started;
        long maximum = 1 + elapsed / POLL_NANOS;
        require(marker.probes <= maximum && stop.probes <= maximum,
                "Filesystem polling is too frequent: marker=" + marker.probes
                + ", stop=" + stop.probes + ", maximum=" + maximum);
        System.out.println("{\"poll_window_ns\":" + elapsed + ",\"marker_probes\":"
                + marker.probes + ",\"stop_probes\":" + stop.probes
                + ",\"maximum_per_file\":" + maximum + "}");
    }

    private static void cancellationIsFinal(File directory, boolean stopFile) throws Exception {
        File marker = new File(directory, stopFile ? "enabled-stop" : "enabled-delete");
        File stop = new File(directory, stopFile ? "stop-created" : "stop-deleted");
        Files.write(marker.toPath(), new byte[0]);
        Files.deleteIfExists(stop.toPath());
        EngineRunGate gate = new EngineRunGate(marker, stop);
        require(gate.prepare() && gate.enabled(), "Run did not start");
        long canceledAt = System.nanoTime();
        if (stopFile) Files.write(stop.toPath(), new byte[0]);
        else Files.delete(marker.toPath());
        while (gate.enabled()) {
            require(System.nanoTime() - canceledAt < CANCELLATION_TIMEOUT_NANOS,
                    "Real marker cancellation did not arrive within 1000 ms");
            LockSupport.parkNanos(100000);
        }
        Files.write(marker.toPath(), new byte[0]);
        Files.deleteIfExists(stop.toPath());
        LockSupport.parkNanos(POLL_NANOS * 2);
        require(!gate.enabled(), "A canceled run became enabled again");
        require(!gate.prepare(), "Preparing a canceled run cleared its fence");
    }

    private static void benchmark(File directory) throws Exception {
        CountingFile marker = new CountingFile(directory, "enabled-bench");
        CountingFile stop = new CountingFile(directory, "stop-bench");
        Files.write(marker.toPath(), new byte[0]);
        Files.deleteIfExists(stop.toPath());
        EngineRunGate gate = new EngineRunGate(marker, stop);
        require(gate.prepare(), "Benchmark run did not prepare");
        marker.probes = 0;
        stop.probes = 0;
        long started = System.nanoTime();
        for (int i = 0; i < 200000; i++) {
            require(gate.enabled(), "Benchmark gate unexpectedly canceled");
        }
        long pollingNanos = System.nanoTime() - started;
        long canceledAt = System.nanoTime();
        Files.delete(marker.toPath());
        while (gate.enabled()) {
            require(System.nanoTime() - canceledAt < CANCELLATION_TIMEOUT_NANOS,
                    "Cancellation timed out after 1000 ms");
            LockSupport.parkNanos(100000);
        }
        long cancellationNanos = System.nanoTime() - canceledAt;
        EngineStatus status = new EngineStatus(directory,
                "0123456789abcdef0123456789abcdef", 2000, 1234);
        long[] writes = new long[500];
        for (int i = 0; i < writes.length; i++) {
            long before = System.nanoTime();
            status.update(i % 2 == 0 ? "SENDING" : "IDLE");
            writes[i] = System.nanoTime() - before;
            String line = new String(Files.readAllBytes(
                    new File(directory, "engine-state.txt").toPath()), StandardCharsets.UTF_8);
            require(line.contains(" v=2 frames_read=0 sent=0 dropped=0 observed_ns=")
                    && line.endsWith("message=none\n"), "Status was not readable");
        }
        Arrays.sort(writes);
        System.out.println("{\"gate_calls\":200000,\"marker_probes\":" + marker.probes
                + ",\"stop_probes\":" + stop.probes + ",\"gate_elapsed_ns\":" + pollingNanos
                + ",\"cancel_observed_ns\":" + cancellationNanos
                + ",\"status_writes\":500,\"status_p50_ns\":" + writes[250]
                + ",\"status_p95_ns\":" + writes[475]
                + ",\"status_max_ns\":" + writes[499] + "}");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class CountingFile extends File {
        long probes;

        CountingFile(File parent, String child) { super(parent, child); }

        @Override public boolean isFile() { probes++; return super.isFile(); }
        @Override public boolean exists() { probes++; return super.exists(); }
    }
}
