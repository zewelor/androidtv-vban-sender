package app.vbansender;

import com.cgutman.adblib.AdbBase64;
import com.cgutman.adblib.AdbCrypto;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;

/** Small CLI for a loopback-only fake ADB peer; fixture keys are created under build/. */
public final class AdbClientHarness {
    private static final String FIXTURE_COMMAND = "printf 'fixture-output'; # UTF-8: café ☕\n"
            + "CLASSPATH='apk' nohup setsid app_process /system/bin/app.vbansender.ProofService "
            + "</dev/null >log 2>&1 & wait_status=$?; exit $wait_status";
    private static final AdbBase64 BASE64 = new AdbBase64() {
        @Override
        public String encodeToString(byte[] data) {
            return Base64.getEncoder().encodeToString(data);
        }
    };

    private AdbClientHarness() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "fixture".equals(args[0])) {
            createFixtureKeys(new File(args[1]));
            return;
        }
        if (args.length != 4 || !"shell".equals(args[0])) {
            throw new IllegalArgumentException(
                    "usage: fixture <build-directory> | shell <port> <timeout-ms> <key-directory>");
        }

        int port = Integer.parseInt(args[1]);
        int timeoutMs = Integer.parseInt(args[2]);
        AdbCrypto crypto = loadFixtureKeys(new File(args[3]));
        try {
            System.out.println(LocalAdb.shell(crypto, port, FIXTURE_COMMAND, timeoutMs));
            System.out.println("HARNESS_SUCCESS");
        } catch (LocalAdb.CommandFailedException e) {
            System.err.println("HARNESS_COMMAND_FAILED exit=" + e.getExitCode()
                    + " output=" + Base64.getEncoder().encodeToString(
                            e.getOutput().getBytes("UTF-8")));
            System.exit(2);
        } catch (Exception e) {
            System.err.println("HARNESS_ERROR " + e.getClass().getName() + ": " + e.getMessage());
            System.exit(1);
        }
    }

    private static void createFixtureKeys(File directory) throws Exception {
        File buildDirectory = new File("build").getCanonicalFile();
        File canonicalDirectory = directory.getCanonicalFile();
        if (!canonicalDirectory.toPath().startsWith(buildDirectory.toPath())
                || canonicalDirectory.equals(buildDirectory)) {
            throw new IllegalArgumentException("fixture keys must be stored below build/");
        }
        if (!canonicalDirectory.isDirectory() && !canonicalDirectory.mkdirs()) {
            throw new IllegalStateException("could not create fixture key directory");
        }
        File privateKey = new File(canonicalDirectory, "adb-private.der");
        File publicKey = new File(canonicalDirectory, "adb-public.der");
        if (!privateKey.exists() && !privateKey.createNewFile()) {
            throw new IllegalStateException("could not create fixture private key file");
        }
        Files.setPosixFilePermissions(privateKey.toPath(),
                PosixFilePermissions.fromString("rw-------"));
        AdbCrypto.generateAdbKeyPair(BASE64).saveAdbKeyPair(privateKey, publicKey);
        System.out.println("FIXTURE_KEYS_CREATED");
    }

    private static AdbCrypto loadFixtureKeys(File directory) throws Exception {
        File canonicalDirectory = directory.getCanonicalFile();
        File buildDirectory = new File("build").getCanonicalFile();
        if (!canonicalDirectory.toPath().startsWith(buildDirectory.toPath())) {
            throw new IllegalArgumentException("fixture keys must be loaded from below build/");
        }
        return AdbCrypto.loadAdbKeyPair(BASE64,
                new File(canonicalDirectory, "adb-private.der"),
                new File(canonicalDirectory, "adb-public.der"));
    }
}
