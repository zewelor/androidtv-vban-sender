package app.vbansender.app;

import android.content.Context;
import android.util.Base64;
import com.cgutman.adblib.AdbBase64;
import com.cgutman.adblib.AdbCrypto;

import app.vbansender.LocalAdb;
import app.vbansender.Heartbeat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/** Shared finite proof used from the visible screen and boot receiver. */
public final class SelfAdbProof {
    public static final class ConnectionUnavailable extends IOException {
        ConnectionUnavailable(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }

    public interface Progress {
        void report(String message);
    }

    static synchronized AdbCrypto keys(Context context) throws Exception {
        File privateKey = new File(context.getFilesDir(), "adb-private.bin");
        File publicKey = new File(context.getFilesDir(), "adb-public.bin");
        AdbBase64 base64 = new AdbBase64() {
            @Override
            public String encodeToString(byte[] bytes) {
                return Base64.encodeToString(bytes, Base64.NO_WRAP);
            }
        };
        if (privateKey.exists() != publicKey.exists()) {
            throw new IllegalStateException("Incomplete app key; ADB authorization unchanged");
        }
        if (privateKey.exists()) {
            return AdbCrypto.loadAdbKeyPair(base64, privateKey, publicKey);
        }
        AdbCrypto crypto = AdbCrypto.generateAdbKeyPair(base64);
        crypto.saveAdbKeyPair(privateKey, publicKey);
        return crypto;
    }

    public static String run(Context context, Progress progress, int connectTimeoutMs) throws Exception {
        return run(context, progress, connectTimeoutMs, Heartbeat.DEFAULT_SECONDS);
    }

    public static String run(Context context, Progress progress, int connectTimeoutMs,
            int heartbeatSeconds) throws Exception {
        if (heartbeatSeconds < 10 || heartbeatSeconds > Heartbeat.MAX_SECONDS) {
            throw new IllegalArgumentException("Proof duration must be 10.." + Heartbeat.MAX_SECONDS);
        }
        AdbCrypto crypto = keys(context);
        String publicKey = new String(crypto.getAdbPublicKeyPayload(), StandardCharsets.UTF_8).split(" ")[0];
        byte[] digest = MessageDigest.getInstance("MD5").digest(Base64.decode(publicKey, Base64.DEFAULT));
        StringBuilder fingerprint = new StringBuilder();
        for (byte value : digest) {
            if (fingerprint.length() > 0) {
                fingerprint.append(':');
            }
            fingerprint.append(String.format(Locale.ROOT, "%02X", value & 255));
        }
        progress.report("App key: " + fingerprint + "\nConnecting to 127.0.0.1:5555…");
        String identity;
        try {
            identity = LocalAdb.shell(crypto, 5555, "id", connectTimeoutMs);
        } catch (IOException error) {
            throw new ConnectionUnavailable(error);
        }
        if (!identity.contains("uid=2000(shell)")) {
            throw new IllegalStateException("Unexpected identity: " + identity);
        }
        String run = UUID.randomUUID().toString().replace("-", "");
        String apk = context.getApplicationInfo().sourceDir;
        if (!apk.matches("/[a-zA-Z0-9_./=+~-]+")) {
            throw new IllegalStateException("Unexpected APK path");
        }
        LocalAdb.shell(crypto, 5555,
                "mkdir -p /data/local/tmp/vban-sender && chmod 700 /data/local/tmp/vban-sender", 10000);
        String launch = "CLASSPATH='" + apk + "' /system/bin/nohup /system/bin/setsid "
                + "app_process / app.vbansender.Heartbeat " + run + " " + heartbeatSeconds
                + " </dev/null >/data/local/tmp/vban-sender/heartbeat.log 2>&1 & "
                + "launch_status=$?; [ \"$launch_status\" -eq 0 ] || exit \"$launch_status\"; "
                + "ready_attempt=0; while [ \"$ready_attempt\" -lt 50 ]; do "
                + "if grep -q '^run=" + run + " uid=2000 ' /data/local/tmp/vban-sender/heartbeat.txt; "
                + "then exit 0; fi; ready_attempt=$((ready_attempt + 1)); sleep 0.1; done; "
                + "cat /data/local/tmp/vban-sender/heartbeat.log >&2; exit 1";
        // Keep the launch channel open until the new detached process has acknowledged
        // startup. A shell background-job status alone does not prove nohup/setsid ran.
        LocalAdb.shell(crypto, 5555, launch, 10000);
        progress.report("Local connection works. Launch channel closed; checking heartbeat…\nrun=" + run);
        Thread.sleep(3000);
        String first = LocalAdb.shell(crypto, 5555, "cat /data/local/tmp/vban-sender/heartbeat.txt", 10000);
        Thread.sleep(4000);
        String second = LocalAdb.shell(crypto, 5555, "cat /data/local/tmp/vban-sender/heartbeat.txt", 10000);
        if (!first.contains("run=" + run + " uid=2000 ")
                || !second.contains("run=" + run + " uid=2000 ")
                || !first.contains(" state=RUNNING") || !second.contains(" state=RUNNING")
                || first.equals(second)) {
            throw new IllegalStateException("Heartbeat did not survive launch channel closure: " + first + second);
        }
        return "PASS: own key, local ADB and heartbeat after launch channel closure.\n"
                + second.trim() + "\nProcess will stop after " + heartbeatSeconds + " seconds.";
    }
}
