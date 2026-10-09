package app.vbansender;

import java.io.IOException;

/** The audio service invalidated the recorder; release it before creating another. */
final class CaptureRestartException extends IOException {
    CaptureRestartException() {
        super("AudioRecord became invalid; recreating capture");
    }
}
