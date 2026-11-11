package app.vbansender;

import android.os.Build;
import android.os.Process;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

/** Clears an earlier Stop only while no capture owner holds the shared lease. */
public final class EnginePreparation {
    private static final File DIRECTORY = new File("/data/local/tmp/vban-sender");

    private EnginePreparation() { }

    public static void main(String[] args) {
        try {
            if (args.length != 0 || Process.myUid() != 2000 || Build.VERSION.SDK_INT < 31) {
                throw new IllegalArgumentException("Preparation requires authorized ADB shell and Android 12+");
            }
            clearStop(DIRECTORY);
        } catch (Exception error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static void clearStop(File directory) throws IOException {
        if (!directory.isDirectory()) {
            throw new IOException("Capture directory does not exist");
        }
        try (RandomAccessFile file = new RandomAccessFile(new File(directory, "engine.lock"), "rw");
                FileLock lock = file.getChannel().tryLock()) {
            if (lock == null) throw new IOException("A capture owner is already running");
            File stop = new File(directory, "stop");
            if (stop.exists() && !stop.delete()) {
                throw new IOException("Cannot clear the previous Stop");
            }
        } catch (OverlappingFileLockException busy) {
            throw new IOException("A capture owner is already running", busy);
        }
    }
}
