package app.vbansender;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.locks.LockSupport;

/** Finite quiet L=440 Hz / R=880 Hz probe using the production VBAN encoder. */
public final class ToneSender {
    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        SenderConfig config = new SenderConfig(args);
        VbanPacket encoder = new VbanPacket(config.stream);
        byte[] pcm = new byte[config.packetFrames * VbanPacket.FRAME_BYTES];
        ByteBuffer samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        int packets = config.seconds * VbanPacket.SAMPLE_RATE / config.packetFrames;
        int sent = 0;
        int dropped = 0;
        long started = System.nanoTime();
        try (DatagramChannel channel = DatagramChannel.open()) {
            channel.connect(config.destination);
            channel.configureBlocking(false);
            for (int counter = 0; counter < packets; counter++) {
                long due = started + (long) counter * config.packetFrames * 1000000000L / VbanPacket.SAMPLE_RATE;
                long wait;
                while ((wait = due - System.nanoTime()) > 0) {
                    LockSupport.parkNanos(wait);
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Tone sender interrupted");
                    }
                }
                samples.clear();
                for (int i = 0; i < config.packetFrames; i++) {
                    long frame = (long) counter * config.packetFrames + i;
                    samples.putShort((short) Math.round(1000 * Math.sin(2 * Math.PI * 440 * frame / 48000)));
                    samples.putShort((short) Math.round(1000 * Math.sin(2 * Math.PI * 880 * frame / 48000)));
                }
                ByteBuffer packet = encoder.encode(pcm, 0, config.packetFrames, counter);
                int size = packet.remaining();
                int written = channel.write(packet);
                if (written == size) {
                    sent++;
                } else if (written == 0) {
                    dropped++;
                } else {
                    throw new IllegalStateException("Partial UDP datagram");
                }
            }
        }
        System.out.println("{\"sent\":" + sent + ",\"dropped\":" + dropped + "}");
    }
}
