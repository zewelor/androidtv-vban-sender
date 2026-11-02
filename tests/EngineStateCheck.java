package app.vbansender.app;

/** Strict parser checks for both legacy and v2 engine status records. */
public final class EngineStateCheck {
    private static final String RUN = "0123456789abcdef0123456789abcdef";

    public static void main(String[] args) {
        String legacy = "run=" + RUN + " uid=2000 pid=1234 state=IDLE message=none\n";
        EngineState old = EngineState.parse(legacy);
        require(old != null && !old.metricsAvailable && old.pid == 1234
                && "IDLE".equals(old.state) && "none".equals(old.message),
                "Legacy state was not accepted as metrics unavailable");

        EngineState state = EngineState.parse(v2("SENDING", 512, 2, 1, 1000, 900, 950, "none"));
        require(state.metricsAvailable && state.framesRead == 512 && state.sent == 2
                && state.dropped == 1 && state.observedNanos == 1000
                && state.lastReadNanos == 900 && state.lastSendNanos == 950,
                "v2 counters or timestamps were not parsed");
        EngineState idle = EngineState.parse(v2("IDLE", 0, 0, 0, 1000, 0, 0, "none"));
        require(idle.metricsAvailable && idle.framesRead == 0,
                "A zero-data IDLE heartbeat was not accepted");
        EngineState droppedOnly = EngineState.parse(v2("SENDING", 2, 0, 2, 1000, 900, 0, "none"));
        require(droppedOnly.metricsAvailable && droppedOnly.sent == 0
                && droppedOnly.dropped == 2 && droppedOnly.lastSendNanos == 0,
                "Dropped packets must not claim a successful send timestamp");
        require(EngineState.parse("") == null, "Missing state should remain unavailable");

        rejects(v2("SENDING", 1, 1, 1, 1000, 900, 950, "none"), "counter total exceeds frames");
        rejects(v2("SENDING", 1, 0, 0, 1000, 0, 0, "none"), "read timestamp missing");
        rejects(v2("SENDING", 0, 0, 0, 1000, 1, 0, "none"), "read timestamp without frames");
        rejects(v2("IDLE", 0, 0, 0, 999, 0, 1000, "none"), "send timestamp after observation");
        rejects(v2("IDLE", 0, 0, 0, 1000, 0, 1, "none"), "send timestamp without successful packet");
        rejects(v2("IDLE", 0, 0, 0, 1, 2, 0, "none"), "read timestamp after observation");
        rejects(v2("IDLE", 0, 0, 0, 1, 0, 0, "none").replace("frames_read=0", "frames_read=00"),
                "noncanonical counter");
        rejects(v2("IDLE", 0, 0, 0, 1, 0, 0, "none").replace("v=2", "v=3"), "unknown version");
        rejects(v2("IDLE", 0, 0, 0, 1, 0, 0, "none").replace("observed_ns=1", "observed_ns=9223372036854775808"),
                "timestamp overflow");
        rejects("run=" + RUN + " uid=2000 pid=999999999999 state=IDLE message=none\n", "pid overflow");
        StringBuilder oversized = new StringBuilder();
        for (int i = 0; i < 1100; i++) oversized.append('x');
        rejects(v2("IDLE", 0, 0, 0, 1, 0, 0, oversized.toString()), "oversized status");
        System.out.println("ENGINE_STATE_TELEMETRY_PASS");
    }

    private static String v2(String state, long framesRead, long sent, long dropped,
            long observed, long lastRead, long lastSend, String message) {
        return "run=" + RUN + " uid=2000 pid=1234 state=" + state
                + " v=2 frames_read=" + framesRead + " sent=" + sent + " dropped=" + dropped
                + " observed_ns=" + observed + " last_read_ns=" + lastRead
                + " last_send_ns=" + lastSend + " message=" + message + "\n";
    }

    private static void rejects(String text, String description) {
        try {
            EngineState.parse(text);
            throw new AssertionError("Accepted invalid state: " + description);
        } catch (IllegalStateException expected) {
            // Invalid engine state must be rejected before the backend trusts it.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
