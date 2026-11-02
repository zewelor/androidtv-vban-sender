package app.vbansender;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Process;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.PortUnreachableException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileLock;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;

/** Finite capture proof. Pilot/volume compatibility must be measured before product use. */
public final class CaptureSender {
    private static final File DIRECTORY = new File("/data/local/tmp/vban-sender");
    private static final File STOP = new File(DIRECTORY, "stop");
    private static final PacketWriter CHANNEL_WRITER = new PacketWriter() {
        @Override
        public int write(DatagramChannel channel, ByteBuffer packet) throws IOException {
            return channel.write(packet);
        }
    };

    public static void main(String[] args) {
        try {
            start(args);
        } catch (Exception error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void start(String[] args) throws Exception {
        boolean appAware = args.length == 6 && "--app-aware".equals(args[5]);
        SenderConfig config = new SenderConfig(appAware ? Arrays.copyOf(args, 5) : args);
        if (Process.myUid() != 2000 || Build.VERSION.SDK_INT < 31) {
            throw new IllegalStateException("Capture proof requires authorized ADB shell and Android 12+");
        }
        if (!DIRECTORY.isDirectory()) {
            throw new IllegalStateException("Create the private shell work directory using tools/device.py");
        }
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(DIRECTORY, "engine.lock"), "rw");
                FileLock lock = lockFile.getChannel().tryLock()) {
            if (lock == null) {
                throw new IllegalStateException("A capture sender is already running");
            }
            if (STOP.exists() && !STOP.delete()) {
                throw new IllegalStateException("Cannot clear the previous stop marker");
            }
            long deadline = System.nanoTime() + config.seconds * 1000000000L;
            BooleanSupplier enabled = new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                    return !STOP.exists();
                }
            };
            if (appAware) {
                System.out.println("APP_AWARE_STARTED: finite observer; unsupported applications keep normal audio");
                try (ForegroundWatcher watcher = new ForegroundWatcher()) {
                    watcher.start();
                    AppAwareController.run(watcher, new AppAwareController.Capture() {
                        @Override
                        public void run(BooleanSupplier active) throws Exception {
                            CaptureSender.run(config, active);
                        }
                    }, enabled, deadline);
                }
                System.out.println("APP_AWARE_STOPPED: observer and capture released");
            } else {
                run(config, new BooleanSupplier() {
                    @Override
                    public boolean getAsBoolean() {
                        return enabled.getAsBoolean() && System.nanoTime() < deadline;
                    }
                });
            }
        }
    }

    private static void run(SenderConfig config, BooleanSupplier keepRunning) throws Exception {
        runCapture(config, keepRunning, null, new CaptureProgress(), null, true, CHANNEL_WRITER);
    }

    static void runContinuous(OutputConfig config, BooleanSupplier keepRunning,
            RecordingStarted recordingStarted, boolean diagnostics) throws Exception {
        runCapture(config, keepRunning, recordingStarted, new CaptureProgress(), null, diagnostics,
                CHANNEL_WRITER);
    }

    static void runContinuous(OutputConfig config, BooleanSupplier keepRunning,
            RecordingStarted recordingStarted, CaptureProgress progress, ProgressListener listener)
            throws Exception {
        runContinuous(config, keepRunning, recordingStarted, progress, listener, false);
    }

    static void runContinuous(OutputConfig config, BooleanSupplier keepRunning,
            RecordingStarted recordingStarted, CaptureProgress progress, ProgressListener listener,
            boolean diagnostics) throws Exception {
        runContinuous(config, keepRunning, recordingStarted, progress, listener, diagnostics,
                CHANNEL_WRITER);
    }

    static void runContinuous(OutputConfig config, BooleanSupplier keepRunning,
            RecordingStarted recordingStarted, CaptureProgress progress, ProgressListener listener,
            boolean diagnostics, PacketWriter writer) throws Exception {
        runCapture(config, keepRunning, recordingStarted, progress, listener, diagnostics, writer);
    }

    interface RecordingStarted {
        void onRecording() throws Exception;
    }

    interface ProgressListener {
        void onProgress() throws Exception;
    }

    interface PacketWriter {
        int write(DatagramChannel channel, ByteBuffer packet) throws IOException;
    }

    private static void runCapture(OutputConfig config, BooleanSupplier keepRunning,
            RecordingStarted recordingStarted, CaptureProgress progress, ProgressListener listener,
            boolean diagnostics, PacketWriter writer) throws Exception {
        if (!keepRunning.getAsBoolean()) {
            return;
        }
        int minimum = AudioRecord.getMinBufferSize(VbanPacket.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) {
            throw new IllegalStateException("PCM capture format unsupported: " + minimum);
        }
        AudioRecord recorder = new AudioRecord.Builder()
                .setContext(new ShellContext())
                .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(VbanPacket.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(minimum * 2,
                        CapturePackets.BLOCK_FRAMES * VbanPacket.FRAME_BYTES))
                .build();
        Thread cleanup = new Thread(new Runnable() {
            @Override
            public void run() {
                recorder.release();
            }
        }, "capture-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);
        byte[] pcm = new byte[CapturePackets.BLOCK_FRAMES * VbanPacket.FRAME_BYTES];
        try (DatagramChannel channel = DatagramChannel.open()) {
            channel.connect(config.destination);
            channel.configureBlocking(false);
            PacketOutput output = new PacketOutput(channel, config.stream, diagnostics,
                    progress, listener, writer);
            if (recorder.getSampleRate() != 48000 || recorder.getChannelCount() != 2
                    || recorder.getAudioFormat() != AudioFormat.ENCODING_PCM_16BIT) {
                throw new IllegalStateException("Actual capture format differs from VBAN format");
            }
            if (!keepRunning.getAsBoolean()) {
                return;
            }
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("AudioRecord did not start");
            }
            long callbackStarted = diagnostics ? System.nanoTime() : 0;
            if (recordingStarted != null) {
                recordingStarted.onRecording();
            }
            if (diagnostics) {
                output.startupCallbackNanos = System.nanoTime() - callbackStarted;
                output.captureBufferFrames = recorder.getBufferSizeInFrames();
                System.out.println("STARTED: finite REMOTE_SUBMIX capture; monotonic_ns=" + System.nanoTime()
                        + "; buffer_frames=" + recorder.getBufferSizeInFrames());
            }
            while (keepRunning.getAsBoolean()) {
                long readStarted = diagnostics ? System.nanoTime() : 0;
                int read = recorder.read(pcm, 0, pcm.length, AudioRecord.READ_NON_BLOCKING);
                long readFinished = System.nanoTime();
                if (diagnostics) output.observeRead(readStarted, readFinished, read);
                progress.observeRead(readFinished,
                        read > 0 && read % VbanPacket.FRAME_BYTES == 0
                                ? read / VbanPacket.FRAME_BYTES : 0);
                if (listener != null) listener.onProgress();
                if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                    throw new CaptureRestartException();
                }
                if (read < 0 || read % 4 != 0) {
                    throw new IllegalStateException("Invalid AudioRecord read: " + read);
                }
                if (read == 0) {
                    LockSupport.parkNanos(1000000);
                    continue;
                }
                CapturePackets.send(pcm, read, config.packetFrames, output, keepRunning);
            }
            if (diagnostics) output.report("CAPTURE_ENDED");
        } finally {
            recorder.release();
            Runtime.getRuntime().removeShutdownHook(cleanup);
            if (diagnostics) {
                System.out.println("STOPPED: capture released; monotonic_ns=" + System.nanoTime()
                        + "; verify normal audio output on the box");
            }
        }
    }

    private static final class PacketOutput implements CapturePackets.Output {
        private final DatagramChannel channel;
        private final VbanPacket encoder;
        private final boolean reportStatistics;
        private final CaptureProgress progress;
        private final ProgressListener listener;
        private final PacketWriter writer;
        private long sent;
        private long dropped;
        private long frames;
        private int peak;
        private int counter;
        private long reportDue;
        private long previousReadNanos;
        private long maxReadGapNanos;
        private long maxReadNanos;
        private long emptyReads;
        private long startupCallbackNanos;
        private int captureBufferFrames;

        PacketOutput(DatagramChannel channel, String stream, boolean reportStatistics) {
            this(channel, stream, reportStatistics, new CaptureProgress(), null, CHANNEL_WRITER);
        }

        PacketOutput(DatagramChannel channel, String stream, boolean reportStatistics,
                CaptureProgress progress, ProgressListener listener, PacketWriter writer) {
            this.channel = channel;
            this.encoder = new VbanPacket(stream);
            this.reportStatistics = reportStatistics;
            this.progress = progress;
            this.listener = listener;
            this.writer = writer;
        }

        @Override
        public void send(byte[] pcm, int offset, int count) throws Exception {
            if (reportStatistics) {
                for (int i = offset; i < offset + count * VbanPacket.FRAME_BYTES; i += 2) {
                    int sample = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
                    peak = Math.max(peak, Math.abs(sample));
                }
                frames += count;
            }
            ByteBuffer packet = encoder.encode(pcm, offset, count, counter++);
            int size = packet.remaining();
            int written;
            try {
                written = writer.write(channel, packet);
            } catch (PortUnreachableException unavailable) {
                // A temporary receiver outage loses this packet, not the capture session.
                written = 0;
            }
            if (written != size && written != 0) {
                throw new IllegalStateException("Partial UDP datagram");
            }
            progress.observeSend(System.nanoTime(), written == size);
            if (listener != null) listener.onProgress();
            if (reportStatistics) {
                if (written == size) {
                    sent++;
                } else {
                    dropped++;
                }
                long now = System.nanoTime();
                if (now >= reportDue) {
                    report("SENDING");
                    peak = 0;
                    reportDue = now + 1000000000L;
                }
            }
        }

        private void observeRead(long started, long finished, int bytes) {
            if (previousReadNanos != 0) {
                maxReadGapNanos = Math.max(maxReadGapNanos, started - previousReadNanos);
            }
            previousReadNanos = started;
            maxReadNanos = Math.max(maxReadNanos, finished - started);
            if (bytes == 0) emptyReads++;
        }

        private void report(String state) {
            System.out.println("{\"state\":\"" + state + "\",\"frames\":" + frames
                    + ",\"sent\":" + sent + ",\"dropped\":" + dropped + ",\"peak\":" + peak
                    + ",\"read_gap_max_ns\":" + maxReadGapNanos
                    + ",\"read_duration_max_ns\":" + maxReadNanos
                    + ",\"empty_reads\":" + emptyReads
                    + ",\"startup_callback_ns\":" + startupCallbackNanos
                    + ",\"capture_buffer_frames\":" + captureBufferFrames + "}");
        }
    }
}
