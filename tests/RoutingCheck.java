package app.vbansender;

/** Observable policy decisions, including ambiguous foreground and stale observations. */
public final class RoutingCheck {
    public static void main(String[] args) {
        ForegroundPolicy policy = new ForegroundPolicy();
        long now = 10000000000L;
        require(!policy.captureAllowed(now), "startup must keep normal audio");
        policy.observe("com.google.android.youtube.tv", now);
        require(!policy.captureAllowed(now), "do not start on an unstable foreground");
        now += 1000000000L;
        policy.observe("com.google.android.youtube.tv", now);
        require(policy.captureAllowed(now), "stable YouTube must allow capture");
        policy.observe("com.netflix.ninja", now + 1);
        require(!policy.captureAllowed(now + 1), "Netflix must stop capture immediately");
        policy.observe("com.amazon.amazonvideo.livingroom", now + 2);
        require(!policy.captureAllowed(now + 2), "do not reuse YouTube stability for Prime");
        now += 1000000002L;
        policy.observe("com.amazon.amazonvideo.livingroom", now);
        require(policy.captureAllowed(now), "stable Prime must allow capture");
        require(!policy.captureAllowed(now + 1500000001L), "stale detection must stop capture");
        policy.observe(null, now + 1500000002L);
        require(!policy.captureAllowed(now + 1500000002L), "unknown foreground must stop");
        policy.observe("com.vendor.launcher", now + 1500000003L);
        require(!policy.captureAllowed(now + 2500000003L), "launcher must keep normal audio");
        policy.observe("org.smarttube.stable", now + 3000000000L);
        policy.observe("org.smarttube.stable", now + 4000000000L);
        require(policy.captureAllowed(now + 4000000000L), "installed SmartTube must allow capture");
        policy.observe("org.smarttube.stable", now + 6000000000L);
        require(!policy.captureAllowed(now + 6000000000L), "fresh observation after a gap needs stability again");

        String netflix = "mResumedActivity: ActivityRecord{a u0 com.netflix.ninja/.MainActivity t1}";
        String youtube = "mResumedActivity: ActivityRecord{b u0 com.google.android.youtube.tv/MainActivity t2}";
        require("com.netflix.ninja".equals(ForegroundPolicy.parse(netflix)), "actual Netflix dump");
        require("com.google.android.youtube.tv".equals(ForegroundPolicy.parse(youtube)), "actual app dump");
        require(ForegroundPolicy.parse("background com.netflix.ninja/.MainActivity") == null,
                "a background process is not the foreground application");
        require(ForegroundPolicy.parse(netflix + "\n" + youtube) == null,
                "different resumed apps are ambiguous");
        require("com.netflix.ninja".equals(ForegroundPolicy.parse(netflix + "\n" + netflix)),
                "duplicate reports for one app remain unambiguous");
        require(ForegroundPolicy.parse("mResumedActivity: null") == null, "empty foreground");
        System.out.println("PASS: routing policy, stability, Netflix exclusion, stale and ambiguous detection");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
