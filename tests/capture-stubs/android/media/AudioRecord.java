package android.media;

import android.content.Context;

public final class AudioRecord {
    public static final int READ_NON_BLOCKING = 1;
    public static final int ERROR_DEAD_OBJECT = -6;
    public static final int RECORDSTATE_RECORDING = 3;
    private static int[] sizes;
    private static byte[][] blocks;
    private static int next;
    public static int releaseCount;

    public static void script(int[] scriptedSizes, byte[][] scriptedBlocks) {
        sizes = scriptedSizes;
        blocks = scriptedBlocks;
        next = 0;
        releaseCount = 0;
    }

    public static int getMinBufferSize(int rate, int channels, int encoding) { return 8192; }

    public static final class Builder {
        public Builder setContext(Context context) { return this; }
        public Builder setAudioSource(int source) { return this; }
        public Builder setAudioFormat(AudioFormat format) { return this; }
        public Builder setBufferSizeInBytes(int bytes) { return this; }
        public AudioRecord build() { return new AudioRecord(); }
    }

    public int getSampleRate() { return 48000; }
    public int getChannelCount() { return 2; }
    public int getAudioFormat() { return AudioFormat.ENCODING_PCM_16BIT; }
    public int getBufferSizeInFrames() { return 4096; }
    public void startRecording() { }
    public int getRecordingState() { return RECORDSTATE_RECORDING; }

    public int read(byte[] target, int offset, int length, int mode) {
        int index = next++;
        int count = sizes[index];
        if (count > 0) System.arraycopy(blocks[index], 0, target, offset, count);
        return count;
    }

    public void release() { releaseCount++; }
}
