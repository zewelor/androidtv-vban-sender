package app.vbansender.app;

import android.content.Context;
import android.content.SharedPreferences;
import com.cgutman.adblib.AdbCrypto;
import app.vbansender.LocalAdb;
import app.vbansender.OutputConfig;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Controls only this APK's nonce and process through its own authorized local ADB. */
final class EngineBackend implements AudioController.Backend {
    private static final String DIRECTORY = "/data/local/tmp/vban-sender/";
    private final Context context;
    private final SharedPreferences prefs;
    private AdbCrypto crypto;

    EngineBackend(Context context) {
        this.context = context.getApplicationContext();
        prefs = AppConfig.prefs(context);
    }

    private String shell(String command) throws Exception {
        if (crypto == null) {
            crypto = SelfAdbProof.keys(context);
        }
        return LocalAdb.shell(crypto, 5555, command, 10000);
    }

    private EngineState read() throws Exception {
        return EngineState.parse(shell("if [ -f " + DIRECTORY + "engine-state.txt ]; then cat "
                + DIRECTORY + "engine-state.txt; fi"));
    }

    private boolean alive(EngineState state) throws Exception {
        String command = "if [ -r /proc/" + state.pid + "/cmdline ]; then "
                + "tr '\\000' '\\n' </proc/" + state.pid + "/cmdline; fi";
        String[] words = shell(command).split("\\n");
        boolean engine = false;
        boolean nonce = false;
        for (String word : words) {
            engine |= "app.vbansender.AudioEngine".equals(word);
            nonce |= state.run.equals(word);
        }
        return engine && nonce;
    }

    private static String marker(String run) {
        if (!run.matches("[a-f0-9]{32}")) {
            throw new IllegalStateException("Invalid saved engine identity");
        }
        return DIRECTORY + "enabled-" + run;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    @Override
    public void start(BooleanSupplier stillEnabled) throws Exception {
        OutputConfig config = AppConfig.read(context);
        if (!stillEnabled.getAsBoolean()) {
            throw new CancellationException("Start cancelled");
        }
        // Only initial, read-only connection failure is retried. Once launched,
        // uncertain engine startup is surfaced and cleanup is explicit.
        for (int attempt = 1; ; attempt++) {
            if (!stillEnabled.getAsBoolean()) {
                throw new CancellationException("Start cancelled");
            }
            try {
                String identity = shell("id");
                if (!identity.contains("uid=2000(shell)")) {
                    throw new IllegalStateException("Unexpected local ADB identity");
                }
                break;
            } catch (IOException error) {
                if (attempt == 6) {
                    throw error;
                }
                for (int i = 0; i < attempt * 20 && stillEnabled.getAsBoolean(); i++) {
                    Thread.sleep(100);
                }
            }
        }
        EngineState existing = read();
        String previous = prefs.getString("active_run", "");
        if (existing != null && alive(existing)) {
            if (!existing.run.equals(previous)) {
                throw new IllegalStateException("Another engine is running; use developer Stop");
            }
            if ("ERROR".equals(existing.state) || "STOPPED".equals(existing.state)) {
                throw new IllegalStateException("Previous engine is still exiting");
            }
            if (!"ENABLED".equals(shell("if [ -f " + marker(previous)
                    + " ]; then echo ENABLED; fi").trim())) {
                throw new IllegalStateException("Previous engine is stopping; retry after OFF");
            }
            return;
        }
        if (!previous.isEmpty()) {
            shell("rm -f " + marker(previous));
        }
        if (!stillEnabled.getAsBoolean()) {
            throw new CancellationException("Start cancelled");
        }
        String apk = context.getApplicationInfo().sourceDir;
        if (!apk.matches("/[a-zA-Z0-9_./=+~-]+")) {
            throw new IllegalStateException("Unexpected APK path");
        }
        String run = UUID.randomUUID().toString().replace("-", "");
        AppConfig.save(prefs.edit().putString("active_run", run));
        shell("mkdir -p " + DIRECTORY + " && chmod 700 " + DIRECTORY);
        // PCM is required by the measured Prime path. Restore the exact saved
        // Android choice on confirmed OFF, including after a failed startup.
        if (!prefs.contains("original_surround")) {
            String original = shell("settings get global encoded_surround_output").trim();
            if (!original.matches("null|[0-3]")) {
                throw new IllegalStateException("Unexpected surround setting: " + original);
            }
            AppConfig.save(prefs.edit().putString("original_surround", original));
        }
        shell("settings put global encoded_surround_output 1");
        if (!stillEnabled.getAsBoolean()) {
            throw new CancellationException("Start cancelled");
        }
        shell("touch " + marker(run));
        if (!stillEnabled.getAsBoolean()) {
            throw new CancellationException("Start cancelled");
        }
        String launch = "CLASSPATH='" + apk + "' /system/bin/nohup /system/bin/setsid "
                + "app_process / app.vbansender.AudioEngine " + config.destination.getAddress().getHostAddress()
                + " " + config.destination.getPort() + " " + quote(config.stream) + " " + config.packetFrames
                + " " + run + " </dev/null >/dev/null 2>&1 & "
                + "launch_status=$?; [ \"$launch_status\" -eq 0 ] || exit \"$launch_status\"; "
                + "ready_attempt=0; while [ \"$ready_attempt\" -lt 50 ]; do "
                + "if grep -q '^run=" + run + " uid=2000 ' " + DIRECTORY + "engine-state.txt; "
                + "then exit 0; fi; ready_attempt=$((ready_attempt + 1)); sleep 0.1; done; exit 1";
        shell(launch);
        EngineState started = read();
        if (started == null || !run.equals(started.run) || !alive(started)
                || "ERROR".equals(started.state) || "STOPPED".equals(started.state)) {
            throw new IllegalStateException(started == null ? "Engine did not start" : started.message);
        }
    }

    @Override
    public void stop() throws Exception {
        String run = prefs.getString("active_run", "");
        if (!run.isEmpty()) {
            // Delete the per-run fence before checking state: a delayed child
            // may not have written any state yet, but must still stay OFF.
            shell("rm -f " + marker(run));
            for (int attempt = 0; ; attempt++) {
                EngineState state = read();
                if (state == null || !run.equals(state.run) || !alive(state)) {
                    break;
                }
                if (attempt == 25) {
                    throw new IllegalStateException("Engine Stop was not confirmed");
                }
                Thread.sleep(200);
            }
        }
        if (prefs.contains("original_surround")) {
            String original = prefs.getString("original_surround", "");
            if (!original.matches("null|[0-3]")) {
                throw new IllegalStateException("Invalid saved surround setting");
            }
            shell("null".equals(original) ? "settings delete global encoded_surround_output"
                    : "settings put global encoded_surround_output " + original);
            if (!original.equals(shell("settings get global encoded_surround_output").trim())) {
                throw new IllegalStateException("Could not restore standard audio settings");
            }
        }
        AppConfig.save(prefs.edit().remove("active_run").remove("original_surround"));
    }

    @Override
    public void check() throws Exception {
        EngineState state = read();
        String run = prefs.getString("active_run", "");
        if (state == null || !state.run.equals(run)) {
            throw new IllegalStateException("Engine stopped. Turn OFF, then ON to retry.");
        }
        if ("ERROR".equals(state.state) || "STOPPED".equals(state.state)) {
            throw new IllegalStateException("Engine " + state.state + ": " + state.message);
        }
        if (!alive(state)) {
            throw new IllegalStateException("Engine stopped. Turn OFF, then ON to retry.");
        }
    }
}
