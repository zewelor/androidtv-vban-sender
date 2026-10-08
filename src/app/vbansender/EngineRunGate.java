package app.vbansender;

import java.io.File;
import java.io.IOException;

/** Per-run APK marker fences a delayed app_process launch before it clears Stop. */
final class EngineRunGate {
    private final File runMarker;
    private final File stopMarker;

    EngineRunGate(File runMarker, File stopMarker) {
        this.runMarker = runMarker;
        this.stopMarker = stopMarker;
    }

    boolean prepare() throws IOException {
        if (!isEmptyMarker()) {
            return false;
        }
        if (stopMarker.exists() && !stopMarker.delete()) {
            throw new IOException("Cannot clear the previous stop marker");
        }
        return true;
    }

    boolean enabled() {
        return runMarker.isFile() && !stopMarker.exists();
    }

    private boolean isEmptyMarker() {
        return runMarker.isFile() && runMarker.length() == 0;
    }
}
