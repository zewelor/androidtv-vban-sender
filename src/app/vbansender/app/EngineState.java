package app.vbansender.app;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates the bounded legacy or v2 state emitted by the owned shell engine. */
final class EngineState {
    private static final String PREFIX = "run=([a-f0-9]{32}) uid=2000 pid=([1-9][0-9]*) "
            + "state=(IDLE|STARTING_CAPTURE|SENDING|STOPPED|ERROR) ";
    private static final Pattern LEGACY = Pattern.compile(PREFIX + "message=([^\\r\\n]*)\\n?");
    private static final Pattern V2 = Pattern.compile(PREFIX
            + "v=2 frames_read=(0|[1-9][0-9]*) sent=(0|[1-9][0-9]*) "
            + "dropped=(0|[1-9][0-9]*) observed_ns=(0|[1-9][0-9]*) "
            + "last_read_ns=(0|[1-9][0-9]*) last_send_ns=(0|[1-9][0-9]*) "
            + "message=([\\x20-\\x7e]*)\\n?");

    final String run;
    final int pid;
    final String state;
    final String message;
    final boolean metricsAvailable;
    final long framesRead;
    final long sent;
    final long dropped;
    final long observedNanos;
    final long lastReadNanos;
    final long lastSendNanos;

    private EngineState(Matcher match, boolean metricsAvailable, long framesRead, long sent,
            long dropped, long observedNanos, long lastReadNanos, long lastSendNanos) {
        run = match.group(1);
        pid = Integer.parseInt(match.group(2));
        state = match.group(3);
        this.metricsAvailable = metricsAvailable;
        if (metricsAvailable) {
            this.framesRead = framesRead;
            this.sent = sent;
            this.dropped = dropped;
            this.observedNanos = observedNanos;
            this.lastReadNanos = lastReadNanos;
            this.lastSendNanos = lastSendNanos;
            message = match.group(10);
        } else {
            this.framesRead = 0;
            this.sent = 0;
            this.dropped = 0;
            this.observedNanos = 0;
            this.lastReadNanos = 0;
            this.lastSendNanos = 0;
            message = match.group(4);
        }
    }

    static EngineState parse(String text) {
        if (text == null) {
            throw new IllegalStateException("Invalid engine status");
        }
        if (text.isEmpty()) {
            return null;
        }
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > 1024) {
            throw new IllegalStateException("Invalid engine status");
        }
        Matcher v2 = V2.matcher(text);
        if (v2.matches()) {
            return parseV2(v2);
        }
        Matcher legacy = LEGACY.matcher(text);
        if (!legacy.matches() || bytes > 512) {
            throw new IllegalStateException("Invalid engine status");
        }
        try {
            return new EngineState(legacy, false, 0, 0, 0, 0, 0, 0);
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("Invalid engine status", invalid);
        }
    }

    private static EngineState parseV2(Matcher match) {
        try {
            long framesRead = Long.parseLong(match.group(4));
            long sent = Long.parseLong(match.group(5));
            long dropped = Long.parseLong(match.group(6));
            long observed = Long.parseLong(match.group(7));
            long lastRead = Long.parseLong(match.group(8));
            long lastSend = Long.parseLong(match.group(9));
            if (sent > framesRead || dropped > framesRead - sent
                    || (framesRead == 0) != (lastRead == 0)
                    || (sent == 0) != (lastSend == 0)
                    || lastRead > observed || lastSend > observed) {
                throw new IllegalStateException("Invalid engine metrics");
            }
            return new EngineState(match, true, framesRead, sent, dropped,
                    observed, lastRead, lastSend);
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("Invalid engine metrics", invalid);
        }
    }
}
