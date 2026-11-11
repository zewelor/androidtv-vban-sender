package app.vbansender;

import java.io.File;

/** Per-run marker fences delayed launches; an emergency Stop always cancels the run. */
final class EngineRunGate {
    private static final long CHECK_INTERVAL_NANOS = 500000000L;
    private final File runMarker;
    private final File stopMarker;
    private boolean checked;
    private boolean canceled;
    private long checkedAt;

    EngineRunGate(File runMarker, File stopMarker) {
        this.runMarker = runMarker;
        this.stopMarker = stopMarker;
    }

    boolean prepare() {
        canceled = canceled || !isEmptyMarker() || stopMarker.exists();
        return !canceled;
    }

    boolean enabled() {
        if (canceled) return false;
        long now = System.nanoTime();
        if (!checked || now - checkedAt >= CHECK_INTERVAL_NANOS) {
            checked = true;
            checkedAt = now;
            // One audio-loop owner polls the filesystem; an observed Stop is final
            // for this run even if its marker is subsequently recreated.
            canceled = !runMarker.isFile() || stopMarker.exists();
        }
        return !canceled;
    }

    private boolean isEmptyMarker() {
        return runMarker.isFile() && runMarker.length() == 0;
    }
}
