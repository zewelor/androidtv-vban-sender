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
    private static final int MAX_STATUS_BYTES = 512;
    private static final String[] STATES = {
            "IDLE", "STARTING_CAPTURE", "SENDING", "STOPPED", "ERROR"
    };

    private final String run;
    private final int uid;
    private final int pid;
    private final File destination;
    private final File temporary;

    EngineStatus(File directory, String run, int uid, int pid) {
        if (run == null || !run.matches("[0-9a-fA-F]{32}")) {
            throw new IllegalArgumentException("Run nonce must be 32 hexadecimal characters");
        }
        if (uid < 0 || pid <= 0) {
            throw new IllegalArgumentException("Invalid process identity");
        }
        this.run = run;
        this.uid = uid;
        this.pid = pid;
        destination = new File(directory, "engine-state.txt");
        temporary = new File(directory, "engine-state.txt.tmp");
    }

    synchronized void update(String state) throws IOException {
        write(state, "none");
    }

    synchronized void updateError(Throwable error) throws IOException {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            message = error.getClass().getSimpleName();
        }
        write("ERROR", message);
    }

    private void write(String state, String message) throws IOException {
        boolean validState = false;
        for (String allowed : STATES) {
            validState |= allowed.equals(state);
        }
        if (!validState) {
            throw new IllegalArgumentException("Invalid engine state: " + state);
        }
        String line = "run=" + run + " uid=" + uid + " pid=" + pid + " state=" + state
                + " message=" + sanitize(message) + "\n";
        byte[] contents = line.getBytes(StandardCharsets.UTF_8);
        if (contents.length > MAX_STATUS_BYTES) {
            throw new IOException("Engine status exceeds 512 bytes");
        }
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(contents);
            output.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            temporary.delete();
            throw error;
        }
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
