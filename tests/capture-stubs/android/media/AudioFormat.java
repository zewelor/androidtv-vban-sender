package android.media;

public final class AudioFormat {
    public static final int CHANNEL_IN_STEREO = 12;
    public static final int ENCODING_PCM_16BIT = 2;

    public static final class Builder {
        public Builder setSampleRate(int value) { return this; }
        public Builder setChannelMask(int value) { return this; }
        public Builder setEncoding(int value) { return this; }
        public AudioFormat build() { return new AudioFormat(); }
    }
}
