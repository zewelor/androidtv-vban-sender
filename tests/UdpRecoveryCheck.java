package app.vbansender;

import java.lang.reflect.Constructor;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;

/** A real loopback receiver disappears and returns while the sender stays enabled. */
public final class UdpRecoveryCheck {
    public static void main(String[] args) throws Exception {
        byte[] pcm = {0x34, 0x12, (byte) 0xcc, (byte) 0xed};
        InetSocketAddress address;
        try (DatagramSocket receiver = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
                DatagramChannel sender = DatagramChannel.open()) {
            address = new InetSocketAddress("127.0.0.1", receiver.getLocalPort());
            sender.connect(address);
            sender.configureBlocking(false);
            // Exercise the production output without constructing Android AudioRecord.
            Constructor<?> constructor = Class.forName("app.vbansender.CaptureSender$PacketOutput")
                    .getDeclaredConstructor(DatagramChannel.class, String.class, boolean.class);
            constructor.setAccessible(true);
            CapturePackets.Output output = (CapturePackets.Output) constructor.newInstance(
                    sender, "RECOVERY-CHECK", false);
            receiver.setSoTimeout(1000);
            output.send(pcm, 0, 1);
            receive(receiver, pcm, 0);
            receiver.close();
            for (int i = 0; i < 20; i++) {
                output.send(pcm, 0, 1);
                Thread.sleep(10);
            }
            try (DatagramSocket returned = new DatagramSocket(address)) {
                returned.setSoTimeout(100);
                boolean received = false;
                for (int i = 0; i < 10 && !received; i++) {
                    output.send(pcm, 0, 1);
                    try {
                        receive(returned, pcm, 21 + i);
                        received = true;
                    } catch (SocketTimeoutException pendingIcmp) {
                        // The last unreachable notification may discard the first send.
                    }
                }
                if (!received) {
                    throw new AssertionError("Sender did not recover when the receiver returned");
                }
            }
            sender.close();
            try {
                output.send(pcm, 0, 1);
                throw new AssertionError("Unexpected socket closure must stay visible");
            } catch (ClosedChannelException expected) {
                // Only remote port unavailability is an expected datagram loss.
            }
        }
        System.out.println("PASS: UDP receiver loss and return, unchanged PCM, sequence gaps, closed-channel failure");
    }

    private static void receive(DatagramSocket receiver, byte[] pcm, int counter) throws Exception {
        byte[] bytes = new byte[64];
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
        receiver.receive(packet);
        if (packet.getLength() != 32 || bytes[0] != 'V' || bytes[1] != 'B'
                || bytes[2] != 'A' || bytes[3] != 'N') {
            throw new AssertionError("Malformed VBAN datagram");
        }
        int sequence = (bytes[24] & 255) | (bytes[25] & 255) << 8
                | (bytes[26] & 255) << 16 | (bytes[27] & 255) << 24;
        if (sequence != counter) {
            throw new AssertionError("Counter reset after loss: " + sequence + " != " + counter);
        }
        for (int i = 0; i < pcm.length; i++) {
            if (bytes[28 + i] != pcm[i]) {
                throw new AssertionError("PCM changed");
            }
        }
    }
}
