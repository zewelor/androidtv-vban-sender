package app.vbansender;

/** Per-engine-run capture counters and monotonic timestamps. */
final class CaptureProgress {
    static final class Snapshot {
        final long framesRead;
        final long sent;
        final long dropped;
        final long observedNanos;
        final long lastReadNanos;
        final long lastSendNanos;

        Snapshot(long framesRead, long sent, long dropped, long observedNanos,
                long lastReadNanos, long lastSendNanos) {
            this.framesRead = framesRead;
            this.sent = sent;
            this.dropped = dropped;
            this.observedNanos = observedNanos;
            this.lastReadNanos = lastReadNanos;
            this.lastSendNanos = lastSendNanos;
        }
    }

    private long framesRead;
    private long sent;
    private long dropped;
    private long observedNanos;
    private long lastReadNanos;
    private long lastSendNanos;

    synchronized void observeRead(long nowNanos, long frames) {
        observedNanos = monotonic(observedNanos, nowNanos);
        if (frames > 0) {
            framesRead = Math.addExact(framesRead, frames);
            lastReadNanos = monotonic(lastReadNanos, nowNanos);
        }
    }

    synchronized void observeSend(long nowNanos, boolean sentSuccessfully) {
        observedNanos = monotonic(observedNanos, nowNanos);
        if (sentSuccessfully) {
            sent = Math.addExact(sent, 1);
            lastSendNanos = monotonic(lastSendNanos, nowNanos);
        } else {
            dropped = Math.addExact(dropped, 1);
        }
    }

    synchronized void observe(long nowNanos) {
        observedNanos = monotonic(observedNanos, nowNanos);
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(framesRead, sent, dropped, observedNanos,
                lastReadNanos, lastSendNanos);
    }

    private static long monotonic(long previous, long current) {
        return Math.max(previous, current);
    }
}
