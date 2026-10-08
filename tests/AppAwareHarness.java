package app.vbansender;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** Actual observer/controller with a synthetic UDP backend, never an Android capture claim. */
public final class AppAwareHarness {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--status-check".equals(args[0])) {
            statusCheck(new File(args[1]));
            return;
        }
        int port = Integer.parseInt(args[0]);
        boolean continuous = "continuous".equals(args[1]);
        int seconds = continuous ? 0 : Integer.parseInt(args[1]);
        File marker = continuous && args.length == 4 ? new File(args[2]) : null;
        File stop = new File(args[continuous && args.length == 4 ? 3 : 2]);
        VbanPacket encoder = new VbanPacket("ROUTING-CHECK");
        byte[] silence = new byte[4];
        int[] counter = {0};
        AppAwareController.Capture capture = active -> {
            try (DatagramChannel channel = DatagramChannel.open()) {
                channel.connect(new InetSocketAddress("127.0.0.1", port));
                System.out.println("SESSION_STARTED");
                while (active.getAsBoolean()) {
                    ByteBuffer packet = encoder.encode(silence, 0, 1, counter[0]++);
                    if (channel.write(packet) != 32) {
                        throw new IllegalStateException("Synthetic datagram not sent");
                    }
                    LockSupport.parkNanos(10000000);
                }
            } finally {
                System.out.println("SESSION_RELEASED");
            }
        };
        try (ForegroundWatcher watcher = new ForegroundWatcher()) {
            watcher.start();
            if (continuous) {
                AppAwareController.runContinuously(watcher, capture,
                        () -> marker == null ? !stop.exists() : marker.exists() && !stop.exists(),
                        state -> System.out.println("ENGINE_STATE " + state));
            } else {
                AppAwareController.run(watcher, capture, () -> !stop.exists(),
                        System.nanoTime() + seconds * 1000000000L);
            }
        }
        System.out.println("CONTROLLER_STOPPED");
    }

    private static void statusCheck(File directory) throws Exception {
        directory.mkdirs();
        OutputConfig output = new OutputConfig(new String[] {"192.0.2.10", "6980", "CHECK", "256"});
        if (output.packetFrames != 256 || output.destination.getPort() != 6980) {
            throw new AssertionError("Continuous output configuration parse failed");
        }
        SenderConfig finite = new SenderConfig(
                new String[] {"192.0.2.10", "6980", "CHECK", "9", "128"});
        if (finite.seconds != 9 || finite.packetFrames != 128) {
            throw new AssertionError("Finite configuration compatibility failed");
        }

        EngineStatus status = new EngineStatus(directory,
                "0123456789abcdef0123456789abcdef", 2000, 1234);
        status.update("IDLE");
        AtomicBoolean reading = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Path state = new File(directory, "engine-state.txt").toPath();
        Thread reader = new Thread(() -> {
            while (reading.get()) {
                try {
                    byte[] contents = Files.readAllBytes(state);
                    String line = new String(contents, StandardCharsets.UTF_8);
                    if (contents.length > 512 || !line.endsWith("\n")
                            || line.indexOf('\n') != line.length() - 1
                            || !line.matches("run=0123456789abcdef0123456789abcdef uid=2000 pid=1234 "
                                    + "state=(IDLE|STARTING_CAPTURE|SENDING|STOPPED|ERROR) message=.*\\n")) {
                        throw new AssertionError("Torn or malformed engine status: " + line);
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                    return;
                }
            }
        }, "status-reader");
        reader.start();
        String[] states = {"IDLE", "STARTING_CAPTURE", "SENDING", "STOPPED"};
        for (int i = 0; i < 2000; i++) {
            status.update(states[i % states.length]);
        }
        StringBuilder longError = new StringBuilder("fixture\n");
        for (int i = 0; i < 350; i++) {
            longError.append('é');
        }
        status.updateError(new IOException(longError.toString()));
        reading.set(false);
        reader.join(2000);
        if (reader.isAlive()) {
            throw new AssertionError("Engine status reader did not stop");
        }
        if (failure.get() != null) {
            throw new AssertionError("Engine status was not atomically readable", failure.get());
        }
        String finalLine = new String(Files.readAllBytes(state), StandardCharsets.UTF_8);
        if (!finalLine.startsWith("run=0123456789abcdef0123456789abcdef uid=2000 pid=1234 state=ERROR message=fixture ")
                || finalLine.length() > 512 || finalLine.indexOf('\n') != finalLine.length() - 1) {
            throw new AssertionError("Engine error state was not bounded and single-line: " + finalLine);
        }
        if (new File(directory, "engine-state.txt.tmp").exists()) {
            throw new AssertionError("Temporary status file remained after replacement");
        }
        gateCheck(directory);
        leaseOrderingCheck(new File(directory, "lease-check"));
        System.out.println("STATUS_CHECK_PASS");
    }

    private static void gateCheck(File directory) throws Exception {
        File marker = new File(directory, "enabled-run");
        File stop = new File(directory, "stop-gate");
        marker.delete();
        stop.createNewFile();
        EngineRunGate gate = new EngineRunGate(marker, stop);
        if (gate.prepare() || !stop.exists()) {
            throw new AssertionError("Canceled-before-start child cleared Stop");
        }
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(marker)) {
            output.write(1);
        }
        if (gate.prepare() || !stop.exists()) {
            throw new AssertionError("Non-empty run marker was accepted");
        }
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(marker)) {
            // An empty marker is the only value accepted for launch.
        }
        if (!gate.prepare() || stop.exists() || !gate.enabled()) {
            throw new AssertionError("Enabled run marker did not prepare the engine");
        }
        if (!marker.delete() || gate.enabled()) {
            throw new AssertionError("Deleting the run marker did not cancel the engine");
        }
        stop.delete();
    }

    private static void leaseOrderingCheck(File directory) throws Exception {
        directory.mkdirs();
        File lockPath = new File(directory, "engine.lock");
        Path statePath = new File(directory, "engine-state.txt").toPath();
        EngineStatus oldRun = new EngineStatus(directory,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 2000, 111);
        EngineStatus newRun = new EngineStatus(directory,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 2000, 222);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch contenderReady = new CountDownLatch(1);
        Thread contender = new Thread(() -> {
            contenderReady.countDown();
            try {
                while (true) {
                    try (RandomAccessFile file = new RandomAccessFile(lockPath, "rw")) {
                        FileLock lease = null;
                        try {
                            lease = file.getChannel().tryLock();
                        } catch (OverlappingFileLockException busy) {
                            // Java reports same-process lock contention instead of waiting.
                        }
                        if (lease != null) {
                            try (FileLock acquired = lease) {
                                newRun.update("IDLE");
                            }
                            return;
                        }
                    }
                    Thread.sleep(2);
                }
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        }, "next-engine-owner");

        RandomAccessFile owner = new RandomAccessFile(lockPath, "rw");
        FileLock ownerLease = owner.getChannel().lock();
        try {
            oldRun.update("SENDING");
            contender.start();
            contenderReady.await();
            AudioEngine.publishFinalStatus(ownerLease, oldRun, null);
            String stopped = new String(Files.readAllBytes(statePath), StandardCharsets.UTF_8);
            if (!stopped.contains("state=STOPPED ") || !ownerLease.isValid()) {
                throw new AssertionError("STOPPED was not published while holding engine.lock");
            }
            AudioEngine.publishFinalStatus(ownerLease, oldRun, new IOException("fixture failure"));
            String error = new String(Files.readAllBytes(statePath), StandardCharsets.UTF_8);
            if (!error.contains("state=ERROR message=fixture failure\n") || !ownerLease.isValid()) {
                throw new AssertionError("ERROR was not published while holding engine.lock");
            }
        } finally {
            ownerLease.release();
            owner.close();
        }
        contender.join(2000);
        if (contender.isAlive()) {
            throw new AssertionError("Next engine owner did not acquire released lock");
        }
        if (failure.get() != null) {
            throw new AssertionError("Next engine owner failed", failure.get());
        }
        String current = new String(Files.readAllBytes(statePath), StandardCharsets.UTF_8);
        if (!current.startsWith("run=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb uid=2000 pid=222 state=IDLE ")) {
            throw new AssertionError("Older run overwrote the newer owner's state: " + current);
        }
        String before = new String(Files.readAllBytes(statePath), StandardCharsets.UTF_8);
        try {
            AudioEngine.publishFinalStatus(ownerLease, oldRun, null);
            throw new AssertionError("Final state accepted without a lease");
        } catch (IllegalStateException expected) {
            // A missing lease must leave the newer run's state untouched.
        }
        String after = new String(Files.readAllBytes(statePath), StandardCharsets.UTF_8);
        if (!before.equals(after)) {
            throw new AssertionError("Invalid final-state write changed the state file");
        }
    }
}
