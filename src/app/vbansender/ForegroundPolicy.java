package app.vbansender;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fail closed; only measured media applications may take over the audio route. */
final class ForegroundPolicy {
    private static final Pattern RESUMED = Pattern.compile(
            "(?m)^\\s*mResumedActivity:\\s*ActivityRecord\\{[^\\r\\n]*?\\bu0\\s+([A-Za-z0-9_.]+)/");
    private static final Pattern SLEEPING = Pattern.compile("\\bmSleeping\\s*=\\s*true\\b");
    private String application;
    private long stableSince;
    private long observedAt;

    static String parse(String dump) {
        if (SLEEPING.matcher(dump).find()) {
            return null;
        }
        Matcher matcher = RESUMED.matcher(dump);
        String application = null;
        while (matcher.find()) {
            String found = matcher.group(1);
            if (application != null && !application.equals(found)) {
                return null;
            }
            application = found;
        }
        return application;
    }

    synchronized void observe(String application, long now) {
        if (this.application == null || !this.application.equals(application)
                || now - observedAt > 1500000000L) {
            stableSince = now;
        }
        this.application = application;
        observedAt = now;
    }

    synchronized boolean captureAllowed(long now) {
        boolean supported = "com.google.android.youtube.tv".equals(application)
                || "org.smarttube.stable".equals(application)
                || "com.amazon.amazonvideo.livingroom".equals(application);
        return supported && now - stableSince >= 1000000000L
                && now - observedAt <= 1500000000L;
    }
}
