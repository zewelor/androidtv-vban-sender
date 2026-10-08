package app.vbansender;

import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/** Bounded shell-only proof of process lifetime; does not capture or send audio. */
public final class Heartbeat {
    public static final int DEFAULT_SECONDS = 60;
    public static final int MAX_SECONDS = 1800;

    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 2000 || args.length < 1 || args.length > 2) {
            throw new IllegalStateException("Heartbeat requires shell UID and a run identifier");
        }
        String run = args[0];
        if (!run.matches("[a-f0-9]{32}")) {
            throw new IllegalArgumentException("Invalid run identifier");
        }
        int seconds = args.length == 2 ? Integer.parseInt(args[1]) : DEFAULT_SECONDS;
        if (seconds < 1 || seconds > MAX_SECONDS) {
            throw new IllegalArgumentException("Heartbeat duration must be 1.." + MAX_SECONDS);
        }
        File directory = new File("/data/local/tmp/vban-sender");
        File stop = new File(directory, "heartbeat.stop");
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(directory, "heartbeat.lock"), "rw");
                FileLock lock = lockFile.getChannel().tryLock();
                RandomAccessFile state = new RandomAccessFile(new File(directory, "heartbeat.txt"), "rw")) {
            if (lock == null) {
                throw new IllegalStateException("Heartbeat already running");
            }
            if (stop.exists() && !stop.delete()) {
                throw new IllegalStateException("Cannot clear previous heartbeat stop marker");
            }
            long deadline = SystemClock.elapsedRealtime() + seconds * 1000L;
            int tick = 0;
            while (!stop.exists() && SystemClock.elapsedRealtime() < deadline) {
                writeState(state, run, tick++, "RUNNING");
                Thread.sleep(1000);
            }
            writeState(state, run, tick, "STOPPED");
            System.out.println("HEARTBEAT_STOPPED run=" + run);
        }
    }

    private static void writeState(RandomAccessFile state, String run, int tick, String status) throws Exception {
        state.setLength(0);
        state.writeBytes("run=" + run + " uid=" + Process.myUid() + " pid=" + Process.myPid()
                + " tick=" + tick + " elapsed_ms=" + SystemClock.elapsedRealtime()
                + " uptime_ms=" + SystemClock.uptimeMillis() + " state=" + status + "\n");
        state.getFD().sync();
    }
}
