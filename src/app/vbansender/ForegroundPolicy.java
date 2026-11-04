package app.vbansender;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exclude Netflix and the resolved Home; fail closed on missing or stale observations. */
final class ForegroundPolicy {
    private static final Pattern RESUMED = Pattern.compile(
            "(?m)^\\s*mResumedActivity:\\s*ActivityRecord\\{[^\\r\\n]*?\\bu0\\s+([A-Za-z0-9_.]+)/");
    private static final Pattern SLEEPING = Pattern.compile("\\bmSleeping\\s*=\\s*true\\b");
    private String application;
    private final String homePackage;
    private long stableSince;
    private long observedAt;

    ForegroundPolicy(String homePackage) {
        if (homePackage == null || homePackage.isEmpty()) {
            throw new IllegalArgumentException("Home package is required");
        }
        this.homePackage = homePackage;
    }

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
        return application != null && !"com.netflix.ninja".equals(application)
                && !homePackage.equals(application)
                && now - stableSince >= 1000000000L
                && now - observedAt <= 1500000000L;
    }
}
