package app.vbansender;

import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicBoolean;

/** Synthetic burst source through the production packet path and a real UDP socket. */
public final class CapturePacketsHarness {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int packetFrames = Integer.parseInt(args[1]);
        String scenario = args[2];
        long period = Long.parseLong(args[3]);
        int blocks = Integer.parseInt(args[4]);
        int[] sequence = {0};
        int[] sourceFrames = {0};
        AtomicBoolean enabled = new AtomicBoolean(true);
        long[] stopRequested = {0};
        byte[] pcm = new byte[CapturePackets.BLOCK_FRAMES * 4];
        VbanPacket encoder = new VbanPacket("PACE-CHECK");
        try (DatagramChannel channel = DatagramChannel.open()) {
            channel.connect(new InetSocketAddress("127.0.0.1", port));
            // Socket/JVM initialization is not part of the synthetic source clock.
            long started = System.nanoTime();
            for (int block = 0; block < blocks && enabled.get(); block++) {
                long due = started + block * period;
                while (System.nanoTime() < due) {
                    LockSupport.parkNanos(Math.min(1000000, due - System.nanoTime()));
                }
                int frames = scenario.equals("partial") ? 513 : CapturePackets.BLOCK_FRAMES;
                for (int i = 0; i < frames; i++) {
                    int sample = (sourceFrames[0] + i) & 0x7fff;
                    pcm[i * 4] = (byte) sample;
                    pcm[i * 4 + 1] = (byte) (sample >>> 8);
                    pcm[i * 4 + 2] = (byte) ~sample;
                    pcm[i * 4 + 3] = (byte) (~sample >>> 8);
                }
                CapturePackets.send(pcm, frames * 4, packetFrames, (data, offset, count) -> {
                    if (scenario.equals("stall") && sequence[0] == 1) {
                        LockSupport.parkNanos(50000000);
                    }
                    if (channel.write(encoder.encode(data, offset, count, sequence[0]++))
                            != 28 + count * 4) {
                        throw new IllegalStateException("Incomplete datagram");
                    }
                    if (scenario.equals("stop")) {
                        enabled.set(false);
                    }
                    if (scenario.equals("stop-wait")) {
                        new Thread(() -> {
                            LockSupport.parkNanos(1000000);
                            stopRequested[0] = System.nanoTime();
                            enabled.set(false);
                        }).start();
                    }
                }, enabled::get);
                sourceFrames[0] += frames;
            }
        }
        long stopLatency = stopRequested[0] == 0 ? 0 : System.nanoTime() - stopRequested[0];
        System.out.println("{\"packets\":" + sequence[0] + ",\"stop_latency_ns\":" + stopLatency + "}");
    }
}
