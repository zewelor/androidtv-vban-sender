/*
 * Portions adapted from scrcpy v3.3.4 FakeContext:
 * Copyright (C) 2018 Genymobile
 * Copyright (C) 2018-2025 Romain Vimont
 * Licensed under Apache-2.0; see third-party-scrcpy-LICENSE.
 * Modified: minimal audio-only context for an Android 12+ shell proof.
 */
package app.vbansender;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Process;

/** Attribution for an already-authorized shell process, as used by scrcpy. */
final class ShellContext extends ContextWrapper {
    ShellContext() {
        super(null);
    }

    @Override
    public String getPackageName() {
        return "com.android.shell";
    }

    @Override
    public String getOpPackageName() {
        return getPackageName();
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public AttributionSource getAttributionSource() {
        return new AttributionSource.Builder(Process.myUid()).setPackageName(getPackageName()).build();
    }
}
