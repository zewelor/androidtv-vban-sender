package app.vbansender;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Independent wire examples, including values that a short UDP run cannot reach. */
public final class ProtocolCheck {
    public static void main(String[] args) {
        VbanPacket packet = new VbanPacket("VBANTX");
        byte[] pcm = new byte[1024];
        pcm[0] = 0x34;
        pcm[1] = 0x12;
        pcm[2] = (byte) 0xcc;
        pcm[3] = (byte) 0xed;
        ByteBuffer wire = packet.encode(pcm, 0, 256, 0);
        byte[] golden = {
            0x56, 0x42, 0x41, 0x4e, 0x03, (byte) 0xff, 0x01, 0x01,
            0x56, 0x42, 0x41, 0x4e, 0x54, 0x58, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        };
        byte[] actual = new byte[28];
        wire.get(actual);
        require(Arrays.equals(golden, actual), "golden VBAN header");
        require(wire.remaining() == 1024, "payload size");
        require(wire.get() == 0x34 && wire.get() == 0x12
                && wire.get() == (byte) 0xcc && wire.get() == (byte) 0xed,
                "PCM bytes must preserve signed little-endian L/R samples");

        wire = packet.encode(pcm, 4, 1, -1);
        require(wire.remaining() == 32 && wire.get(5) == 0, "one-frame packet");
        for (int i = 24; i < 28; i++) {
            require(wire.get(i) == (byte) 0xff, "unsigned counter 0xffffffff");
        }
        wire = packet.encode(pcm, 0, 2, Integer.MAX_VALUE + 1);
        require(wire.get(24) == 0 && wire.get(27) == (byte) 0x80, "counter high bit");
        wire = packet.encode(pcm, 0, 1, -1 + 1);
        for (int i = 24; i < 28; i++) {
            require(wire.get(i) == 0, "counter wrap");
        }

        new VbanPacket("1234567890123456");
        reject(() -> new VbanPacket(""));
        reject(() -> new VbanPacket("12345678901234567"));
        reject(() -> new VbanPacket("audio-\u2603"));
        reject(() -> new VbanPacket("bad\0name"));
        reject(() -> packet.encode(pcm, 0, 0, 0));
        reject(() -> packet.encode(pcm, 0, 257, 0));
        reject(() -> packet.encode(pcm, -1, 1, 0));
        reject(() -> packet.encode(pcm, 1020, 2, 0));
        System.out.println("PASS: golden header, PCM, frame boundaries, unsigned counters, invalid inputs");
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void reject(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Invalid input accepted");
    }
}
