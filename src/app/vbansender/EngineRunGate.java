package app.vbansender;

import java.io.File;
import java.io.IOException;

/** Per-run APK marker fences a delayed app_process launch before it clears Stop. */
final class EngineRunGate {
    private static final long CHECK_INTERVAL_NANOS = 5000000L;
    private final File runMarker;
    private final File stopMarker;
    private boolean checked;
    private boolean canceled;
    private long checkedAt;

    EngineRunGate(File runMarker, File stopMarker) {
        this.runMarker = runMarker;
        this.stopMarker = stopMarker;
    }

    boolean prepare() throws IOException {
        if (canceled || !isEmptyMarker()) {
            return false;
        }
        if (stopMarker.exists() && !stopMarker.delete()) {
            throw new IOException("Cannot clear the previous stop marker");
        }
        return true;
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
