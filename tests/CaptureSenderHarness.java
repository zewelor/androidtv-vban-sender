package app.vbansender;

import android.media.AudioRecord;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Runs the real capture loop against scripted AudioRecord reads and a local UDP receiver. */
public final class CaptureSenderHarness {
    public static void main(String[] args) throws Exception {
        byte[] silence = new byte[8];
        byte[] sound = new byte[] {1, 0, 2, 0, 3, 0, 4, 0};
        AudioRecord.script(new int[] {0, silence.length, sound.length,
                AudioRecord.ERROR_DEAD_OBJECT}, new byte[][] {null, silence, sound, null});
        CaptureProgress progress = new CaptureProgress();
        List<CaptureProgress.Snapshot> reads = new ArrayList<>();
        int[] writes = {0};
        try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            receiver.setSoTimeout(1000);
            OutputConfig config = new OutputConfig(new String[] {"127.0.0.1",
                    Integer.toString(receiver.getLocalPort()), "TELEMETRY", "2"});
            try {
                CaptureSender.runContinuous(config, new BooleanSupplier() {
                    @Override
                    public boolean getAsBoolean() { return true; }
                }, null, progress, new CaptureSender.ProgressListener() {
                    @Override
                    public void onProgress() { reads.add(progress.snapshot()); }
                }, false, (channel, packet) -> {
                    int attempt = writes[0]++;
                    if (attempt == 0) return channel.write(packet);
                    return 0;
                });
                throw new AssertionError("Dead AudioRecord did not request a capture restart");
            } catch (CaptureRestartException expected) {
                // The controller owns restart policy; this sender attempt must release its recorder.
            }
            DatagramPacket received = new DatagramPacket(new byte[256], 256);
            receiver.receive(received);
            if (received.getLength() != 36) {
                throw new AssertionError("Unexpected VBAN packet length: " + received.getLength());
            }
            for (int i = 28; i < received.getLength(); i++) {
                if (received.getData()[i] != 0) {
                    throw new AssertionError("Synthetic silence payload was not preserved");
                }
            }
        }

        if (reads.size() < 4 || reads.get(0).framesRead != 0 || reads.get(0).sent != 0
                || reads.get(0).dropped != 0 || reads.get(0).observedNanos <= 0
                || reads.get(0).lastReadNanos != 0 || reads.get(0).lastSendNanos != 0) {
            throw new AssertionError("An empty read changed data counters or timestamps");
        }
        if (reads.size() < 5 || reads.get(2).lastSendNanos <= 0
                || reads.get(4).lastSendNanos != reads.get(2).lastSendNanos) {
            throw new AssertionError("A dropped packet replaced the last successful send time");
        }
        CaptureProgress.Snapshot finalState = progress.snapshot();
        if (finalState.framesRead != 4 || finalState.sent != 1 || finalState.dropped != 1
                || finalState.lastReadNanos <= 0 || finalState.lastSendNanos <= 0
                || finalState.observedNanos < finalState.lastReadNanos
                || finalState.observedNanos < finalState.lastSendNanos
                || writes[0] != 2 || AudioRecord.releaseCount != 1) {
            throw new AssertionError("Capture counters/restart/release were incorrect: framesRead="
                    + finalState.framesRead + " sent=" + finalState.sent
                    + " dropped=" + finalState.dropped + " writes=" + writes[0]
                    + " releases=" + AudioRecord.releaseCount);
        }
        System.out.println("CAPTURE_SENDER_TELEMETRY_PASS");
    }
}
