package app.vbansender;

/** Finite developer-probe settings layered on the shared VBAN output configuration. */
public final class SenderConfig extends OutputConfig {
    public final int seconds;

    public SenderConfig(String[] args) throws Exception {
        super(outputArguments(args));
        seconds = integer(args[3], 1, 3600, "Seconds");
    }

    private static String[] outputArguments(String[] args) {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "Usage: DESTINATION_IPV4 PORT STREAM SECONDS PACKET_FRAMES");
        }
        return new String[] {args[0], args[1], args[2], args[4]};
    }
}
