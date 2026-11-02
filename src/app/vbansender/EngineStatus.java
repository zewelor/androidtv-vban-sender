package app.vbansender;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Atomic, bounded status file consumed by the companion APK. */
final class EngineStatus {
    private static final int MAX_MESSAGE_CHARS = 300;
    private static final int MAX_STATUS_BYTES = 1024;
    private static final long PUBLISH_INTERVAL_NANOS = 1000000000L;
    private static final String[] STATES = {
            "IDLE", "STARTING_CAPTURE", "SENDING", "STOPPED", "ERROR"
    };

    private final String run;
    private final int uid;
    private final int pid;
    private final File destination;
    private final File temporary;
    private final CaptureProgress progress;
    private String lastState;
    private long lastPublishedNanos;

    EngineStatus(File directory, String run, int uid, int pid) {
        this(directory, run, uid, pid, new CaptureProgress());
    }

    EngineStatus(File directory, String run, int uid, int pid, CaptureProgress progress) {
        if (run == null || !run.matches("[0-9a-fA-F]{32}")) {
            throw new IllegalArgumentException("Run nonce must be 32 hexadecimal characters");
        }
        if (uid < 0 || pid <= 0 || progress == null) {
            throw new IllegalArgumentException("Invalid process identity");
        }
        this.run = run;
        this.uid = uid;
        this.pid = pid;
        this.progress = progress;
        destination = new File(directory, "engine-state.txt");
        temporary = new File(directory, "engine-state.txt.tmp");
    }

    synchronized void update(String state) throws IOException {
        publish(state, "none", false);
    }

    synchronized void heartbeat(String state) throws IOException {
        publish(state, "none", false);
    }

    synchronized void updateError(Throwable error) throws IOException {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            message = error.getClass().getSimpleName();
        }
        publish("ERROR", message, true);
    }

    private void publish(String state, String message, boolean force) throws IOException {
        boolean validState = false;
        for (String allowed : STATES) {
            validState |= allowed.equals(state);
        }
        if (!validState) {
            throw new IllegalArgumentException("Invalid engine state: " + state);
        }
        long now = System.nanoTime();
        boolean changed = !state.equals(lastState);
        if (!force && !changed && now - lastPublishedNanos < PUBLISH_INTERVAL_NANOS) {
            return;
        }
        progress.observe(now);
        CaptureProgress.Snapshot snapshot = progress.snapshot();
        String line = "run=" + run + " uid=" + uid + " pid=" + pid + " state=" + state
                + " v=2 frames_read=" + snapshot.framesRead + " sent=" + snapshot.sent
                + " dropped=" + snapshot.dropped + " observed_ns=" + snapshot.observedNanos
                + " last_read_ns=" + snapshot.lastReadNanos + " last_send_ns=" + snapshot.lastSendNanos
                + " message=" + sanitize(message) + "\n";
        byte[] contents = line.getBytes(StandardCharsets.UTF_8);
        if (contents.length > MAX_STATUS_BYTES) {
            throw new IOException("Engine status exceeds 1024 bytes");
        }
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(contents);
        }
        // This is live IPC, validated against the current run and process. Atomic
        // publication is required; power-loss durability must not delay capture.
        try {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            temporary.delete();
            throw error;
        }
        lastState = state;
        lastPublishedNanos = now;
    }

    private static String sanitize(String input) {
        StringBuilder safe = new StringBuilder(Math.min(input.length(), MAX_MESSAGE_CHARS));
        for (int i = 0; i < input.length() && safe.length() < MAX_MESSAGE_CHARS; i++) {
            char value = input.charAt(i);
            if (value < 32 || value > 126) {
                safe.append(' ');
            } else {
                safe.append(value);
            }
        }
        return safe.toString();
    }
}
