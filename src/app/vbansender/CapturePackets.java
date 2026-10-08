package app.vbansender;

import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;

/** Paces one bounded capture read; each new block follows the source clock. No queue. */
public final class CapturePackets {
    public static final int BLOCK_FRAMES = 1024;

    public interface Output {
        void send(byte[] pcm, int offset, int frames) throws Exception;
    }

    public static int send(byte[] pcm, int bytes, int packetFrames,
            Output output, BooleanSupplier active) throws Exception {
        if (bytes < 0 || bytes > pcm.length || bytes % VbanPacket.FRAME_BYTES != 0
                || bytes > BLOCK_FRAMES * VbanPacket.FRAME_BYTES
                || packetFrames < 1 || packetFrames > VbanPacket.MAX_FRAMES) {
            throw new IllegalArgumentException("Invalid capture block or packet size");
        }
        int totalFrames = bytes / VbanPacket.FRAME_BYTES;
        int offsetFrames = 0;
        long due = System.nanoTime();
        while (offsetFrames < totalFrames) {
            int frames = Math.min(packetFrames, totalFrames - offsetFrames);
            while (true) {
                long remaining = due - System.nanoTime();
                if (!active.getAsBoolean()) {
                    return offsetFrames;
                }
                if (remaining <= 0) {
                    break;
                }
                LockSupport.parkNanos(Math.min(remaining, 1000000));
            }
            output.send(pcm, offsetFrames * VbanPacket.FRAME_BYTES, frames);
            // A delayed send never causes a catch-up burst. Rebase only within this
            // bounded block; the following capture read follows the hardware clock.
            long period = frames * 1000000000L / VbanPacket.SAMPLE_RATE;
            due += period;
            if (due < System.nanoTime()) {
                due = System.nanoTime() + period;
            }
            offsetFrames += frames;
        }
        return offsetFrames;
    }
}
