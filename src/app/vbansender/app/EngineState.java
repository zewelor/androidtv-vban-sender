package app.vbansender.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates the bounded state emitted by the owned shell engine. */
final class EngineState {
    private static final Pattern FORMAT = Pattern.compile(
            "run=([a-f0-9]{32}) uid=2000 pid=([1-9][0-9]*) "
            + "state=(IDLE|STARTING_CAPTURE|SENDING|STOPPED|ERROR) message=([^\\r\\n]*)\\n?");
    final String run;
    final int pid;
    final String state;
    final String message;

    private EngineState(Matcher match) {
        run = match.group(1);
        pid = Integer.parseInt(match.group(2));
        state = match.group(3);
        message = match.group(4);
    }

    static EngineState parse(String text) {
        if (text.isEmpty()) {
            return null;
        }
        Matcher match = FORMAT.matcher(text);
        if (text.length() > 512 || !match.matches()) {
            throw new IllegalStateException("Invalid engine status");
        }
        return new EngineState(match);
    }
}
