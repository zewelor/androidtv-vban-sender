package app.vbansender;

import android.os.Build;
import android.os.Process;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Continuous, app-aware REMOTE_SUBMIX to VBAN engine for the companion APK. */
public final class AudioEngine {
    private static final File DIRECTORY = new File("/data/local/tmp/vban-sender");
    private static final File STOP = new File(DIRECTORY, "stop");

    private AudioEngine() { }

    public static void main(String[] args) {
        try {
            if (args.length != 5) {
                throw new IllegalArgumentException(
                        "Usage: DEST_IPV4 PORT STREAM PACKET_FRAMES RUN_HEX32");
            }
            OutputConfig config = new OutputConfig(Arrays.copyOf(args, 4));
            if (!args[4].matches("[0-9a-fA-F]{32}")) {
                throw new IllegalArgumentException("Run nonce must be 32 hexadecimal characters");
            }
            if (Process.myUid() != 2000 || Build.VERSION.SDK_INT < 31) {
                throw new IllegalStateException("Audio engine requires authorized ADB shell and Android 12+");
            }
            if (!DIRECTORY.isDirectory()) {
                throw new IllegalStateException("Create the private shell work directory using tools/device.py");
            }
            try (RandomAccessFile lockFile = new RandomAccessFile(new File(DIRECTORY, "engine.lock"), "rw");
                    FileLock lock = lockFile.getChannel().tryLock()) {
                if (lock == null) {
                    throw new IllegalStateException("An audio engine or finite capture sender is already running");
                }
                EngineStatus status = new EngineStatus(DIRECTORY, args[4], Process.myUid(), Process.myPid());
                EngineRunGate gate = new EngineRunGate(
                        new File(DIRECTORY, "enabled-" + args[4]), STOP);
                try {
                    if (!gate.prepare()) {
                        return;
                    }
                    status.update("IDLE");
                    runController(config, gate, status);
                    publishFinalStatus(lock, status, null);
                } catch (Exception error) {
                    try {
                        publishFinalStatus(lock, status, error);
                    } catch (Exception statusError) {
                        error.addSuppressed(statusError);
                    }
                    throw error;
                }
            }
        } catch (Exception error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void runController(OutputConfig config, EngineRunGate gate,
            EngineStatus status) throws Exception {
        try (ForegroundWatcher watcher = new ForegroundWatcher()) {
            watcher.start();
            AppAwareController.runContinuously(watcher, new AppAwareController.Capture() {
                @Override
                public void run(BooleanSupplier active) throws Exception {
                    CaptureSender.runContinuous(config, active,
                            new CaptureSender.RecordingStarted() {
                                @Override
                                public void onRecording() throws Exception {
                                    status.update("SENDING");
                                }
                            });
                }
            }, new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                    return gate.enabled();
                }
            }, new AppAwareController.Status() {
                @Override
                public void update(String state) throws Exception {
                    status.update(state);
                }
            });
        }
    }

    static void publishFinalStatus(FileLock lease, EngineStatus status, Exception failure)
            throws Exception {
        if (lease == null || !lease.isValid()) {
            throw new IllegalStateException("Cannot publish final engine status without its lock lease");
        }
        if (failure == null) {
            status.update("STOPPED");
        } else {
            status.updateError(failure);
        }
    }
}
