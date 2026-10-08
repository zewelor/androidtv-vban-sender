package app.vbansender;

import com.cgutman.adblib.AdbConnection;
import com.cgutman.adblib.AdbCrypto;
import com.cgutman.adblib.AdbProtocol;
import com.cgutman.adblib.AdbStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs one bounded shell command through classic ADB on this device's own box. */
public final class LocalAdb {
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private static final int MAX_COMPLETION_TRAILER_BYTES = 64;
    private static final byte[] LOOPBACK = new byte[] {127, 0, 0, 1};
    private static final SecureRandom RANDOM = new SecureRandom();

    private LocalAdb() {
    }

    /**
     * Runs one shell command against localhost ADB and returns its UTF-8 output.
     * A nonzero shell status is reported as {@link CommandFailedException}.
     */
    public static String shell(AdbCrypto crypto, int port, String command, int timeoutMs)
            throws Exception {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("ADB port must be between 1 and 65535");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("ADB timeout must be positive");
        }
        if (command == null) {
            throw new IllegalArgumentException("ADB command must not be null");
        }
        if (command.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("ADB command must not contain NUL");
        }
        final String marker = Deadline.newMarker();
        final String service = "shell:" + wrapCommand(command, marker);
        if (service.getBytes("UTF-8").length + 1 > AdbProtocol.CONNECT_MAXDATA) {
            throw new IllegalArgumentException("ADB shell service exceeds "
                    + AdbProtocol.CONNECT_MAXDATA + " bytes");
        }
        if (crypto == null) {
            throw new IllegalArgumentException("ADB key pair must not be null");
        }

        final long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        final Socket socket = new Socket();
        final Deadline deadline = new Deadline(socket, deadlineNanos, timeoutMs, marker);
        AdbConnection connection = null;
        Exception primaryFailure = null;
        deadline.start();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(LOOPBACK), port), timeoutMs);
            deadline.check();
            connection = AdbConnection.create(socket, crypto);
            connection.connect();
            deadline.check();

            AdbStream stream = connection.open(service);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Pattern markerPattern = Pattern.compile("\\n" + deadline.marker + ":([0-9]+)\\n$");

            while (true) {
                deadline.check();
                byte[] payload;
                try {
                    payload = stream.read();
                } catch (IOException e) {
                    throw new IOException("ADB shell closed before its completion marker", e);
                }
                if ((long) output.size() + payload.length
                        > MAX_OUTPUT_BYTES + MAX_COMPLETION_TRAILER_BYTES) {
                    throw new IOException("ADB shell output exceeded " + MAX_OUTPUT_BYTES + " bytes");
                }
                output.write(payload);

                String response = new String(output.toByteArray(), "UTF-8");
                Matcher completion = markerPattern.matcher(response);
                if (completion.find()) {
                    deadline.check();
                    String result = response.substring(0, completion.start());
                    if (result.getBytes("UTF-8").length > MAX_OUTPUT_BYTES) {
                        throw new IOException("ADB shell output exceeded " + MAX_OUTPUT_BYTES + " bytes");
                    }
                    int exitCode = Integer.parseInt(completion.group(1));
                    if (exitCode != 0) {
                        throw new CommandFailedException(exitCode, result);
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            if (deadline.expiredOrPast()) {
                SocketTimeoutException timeout = new SocketTimeoutException(
                        "ADB shell timed out after " + timeoutMs + " ms");
                timeout.initCause(e);
                IOException closeFailure = deadline.closeFailure();
                if (closeFailure != null) {
                    timeout.addSuppressed(closeFailure);
                }
                primaryFailure = timeout;
                throw timeout;
            }
            primaryFailure = e;
            throw e;
        } finally {
            deadline.stop();
            try {
                if (connection == null) {
                    socket.close();
                } else {
                    connection.close();
                }
            } catch (IOException closeFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(closeFailure);
                } else {
                    throw closeFailure;
                }
            }
        }
    }

    private static String wrapCommand(String command, String marker) {
        String quotedCommand = "'" + command.replace("'", "'\\''") + "'";
        return "/system/bin/sh -c " + quotedCommand
                + "; __vban_sender_status=$?; printf '\\n" + marker
                + ":%d\\n' \"$__vban_sender_status\"";
    }

    /** A shell command that completed with a nonzero status. */
    public static final class CommandFailedException extends IOException {
        private final int exitCode;
        private final String output;

        private CommandFailedException(int exitCode, String output) {
            super("ADB shell command exited with status " + exitCode);
            this.exitCode = exitCode;
            this.output = output;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getOutput() {
            return output;
        }
    }

    private static final class Deadline implements Runnable {
        private final Socket socket;
        private final long deadlineNanos;
        private final int timeoutMs;
        private final String marker;
        private volatile boolean stopped;
        private volatile boolean expired;
        private volatile IOException closeFailure;
        private Thread thread;

        Deadline(Socket socket, long deadlineNanos, int timeoutMs, String marker) {
            this.socket = socket;
            this.deadlineNanos = deadlineNanos;
            this.timeoutMs = timeoutMs;
            this.marker = marker;
        }

        void start() {
            thread = new Thread(this, "local-adb-deadline");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void run() {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining > 0) {
                long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
                int nanos = (int) (remaining - TimeUnit.MILLISECONDS.toNanos(millis));
                try {
                    Thread.sleep(millis, nanos);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (!stopped && System.nanoTime() - deadlineNanos >= 0) {
                expired = true;
                try {
                    socket.close();
                } catch (IOException e) {
                    closeFailure = e;
                }
            }
        }

        void check() throws SocketTimeoutException {
            if (expiredOrPast()) {
                throw new SocketTimeoutException("ADB shell timed out after " + timeoutMs + " ms");
            }
        }

        boolean expiredOrPast() {
            return expired || System.nanoTime() - deadlineNanos >= 0;
        }

        IOException closeFailure() {
            return closeFailure;
        }

        void stop() {
            stopped = true;
            if (thread != null) {
                thread.interrupt();
            }
        }

        private static String newMarker() {
            byte[] value = new byte[16];
            RANDOM.nextBytes(value);
            StringBuilder marker = new StringBuilder(value.length * 2);
            for (byte b : value) {
                marker.append(Character.forDigit((b >>> 4) & 0x0f, 16));
                marker.append(Character.forDigit(b & 0x0f, 16));
            }
            return "__VBAN_SENDER_ADB_" + marker + "__";
        }
    }
}
