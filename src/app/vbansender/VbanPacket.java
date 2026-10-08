package app.vbansender;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** PCM S16LE, 48 kHz, stereo. The returned buffer is reused on the next call. */
public final class VbanPacket {
    public static final int SAMPLE_RATE = 48000;
    public static final int FRAME_BYTES = 4;
    public static final int MAX_FRAMES = 256;
    private final ByteBuffer packet = ByteBuffer.allocate(28 + MAX_FRAMES * FRAME_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);

    public VbanPacket(String stream) {
        if (stream.isEmpty() || stream.length() > 16) {
            throw new IllegalArgumentException("Stream must contain 1–16 printable ASCII characters");
        }
        for (int i = 0; i < stream.length(); i++) {
            if (stream.charAt(i) < 32 || stream.charAt(i) > 126) {
                throw new IllegalArgumentException("Stream must contain printable ASCII characters");
            }
        }
        packet.put(new byte[]{'V', 'B', 'A', 'N', 3, 0, 1, 1});
        packet.put(stream.getBytes(StandardCharsets.US_ASCII));
    }

    public ByteBuffer encode(byte[] pcm, int offset, int frames, int counter) {
        int bytes = frames * FRAME_BYTES;
        if (frames < 1 || frames > MAX_FRAMES || offset < 0 || offset > pcm.length - bytes) {
            throw new IllegalArgumentException("Invalid PCM frame range");
        }
        packet.clear();
        packet.put(5, (byte) (frames - 1));
        packet.putInt(24, counter);
        packet.position(28);
        packet.put(pcm, offset, bytes);
        packet.flip();
        return packet;
    }
}
