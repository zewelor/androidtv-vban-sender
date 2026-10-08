package com.cgutman.adblib;

import java.io.IOException;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.net.Socket;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import app.vbansender.LocalAdb;

/** Focused JVM checks for the vendored ADB transport and local shell adapter. */
public final class AdbClientCheck {
    private AdbClientCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "verify-open-utf8".equals(args[0])) {
            encodesOpenServiceAsUtf8();
            System.out.println("PASS: UTF-8 ADB OPEN service bytes");
            return;
        }
        if (args.length == 4 && "verify-signature".equals(args[0])) {
            verifySignature(args[1], args[2], args[3]);
            return;
        }
        if (args.length == 3 && "verify-upload".equals(args[0])) {
            verifyUploadedKey(args[1], args[2]);
            return;
        }
        preservesQueuedPayloadBeforeClose();
        rejectsMalformedPayloadLengths();
        encodesOpenServiceAsUtf8();
        validatesAdapterArguments();
        System.out.println("PASS: queued ADB data, packet length bounds, adapter arguments");
    }

    private static void preservesQueuedPayloadBeforeClose() throws Exception {
        ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        Socket client = new Socket();
        client.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}),
                listener.getLocalPort()));
        Socket peer = listener.accept();
        AdbConnection connection = AdbConnection.create(client, null);
        try {
            AdbStream stream = new AdbStream(connection, 1);
            byte[] expected = "queued output".getBytes("UTF-8");

            stream.addPayload(expected);
            stream.notifyClose();

            byte[] actual = stream.read();
            if (!Arrays.equals(expected, actual)) {
                throw new AssertionError("queued payload changed before close");
            }

            try {
                stream.read();
            } catch (IOException expectedClosed) {
                return;
            }
            throw new AssertionError("read after draining a closed stream did not fail");
        } finally {
            connection.close();
            peer.close();
            listener.close();
        }
    }

    private static void rejectsMalformedPayloadLengths() throws Exception {
        rejectPayloadLength(-1);
        rejectPayloadLength(AdbProtocol.MAX_PAYLOAD_SIZE + 1);
    }

    private static void encodesOpenServiceAsUtf8() throws Exception {
        String destination = "shell:printf café ☕";
        byte[] packet = AdbProtocol.generateOpen(1, destination);
        AdbProtocol.AdbMessage message = AdbProtocol.AdbMessage.parseAdbMessage(
                new java.io.ByteArrayInputStream(packet));
        byte[] expected = (destination + "\0").getBytes("UTF-8");
        if (!Arrays.equals(expected, message.payload)) {
            throw new AssertionError("ADB OPEN service did not preserve UTF-8 bytes");
        }
    }

    private static void rejectPayloadLength(int length) throws Exception {
        ByteBuffer header = ByteBuffer.allocate(AdbProtocol.ADB_HEADER_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(AdbProtocol.CMD_WRTE);
        header.putInt(1);
        header.putInt(2);
        header.putInt(length);
        header.putInt(0);
        header.putInt(AdbProtocol.CMD_WRTE ^ 0xffffffff);
        try {
            AdbProtocol.AdbMessage.parseAdbMessage(
                    new java.io.ByteArrayInputStream(header.array()));
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("malformed ADB payload length accepted: " + length);
    }

    private static void validatesAdapterArguments() throws Exception {
        rejects(new CheckedRunnable() {
            @Override
            public void run() throws Exception {
                LocalAdb.shell(null, 0, "true", 100);
            }
        });
        rejects(new CheckedRunnable() {
            @Override
            public void run() throws Exception {
                LocalAdb.shell(null, 5555, "true", 0);
            }
        });
        rejects(new CheckedRunnable() {
            @Override
            public void run() throws Exception {
                LocalAdb.shell(null, 5555, null, 100);
            }
        });
        rejects(new CheckedRunnable() {
            @Override
            public void run() throws Exception {
                LocalAdb.shell(null, 5555, "bad\0command", 100);
            }
        });
        rejects(new CheckedRunnable() {
            @Override
            public void run() throws Exception {
                LocalAdb.shell(null, 5555, "true", 100);
            }
        });
        rejectsWithMessage(new CheckedRunnable() {
            @Override
            public void run() throws Exception {
                LocalAdb.shell(null, 5555, repeated('x', 5000), 100);
            }
        }, "exceeds 4096 bytes");
    }

    private static void rejects(CheckedRunnable operation) throws Exception {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("invalid ADB arguments accepted");
    }

    private static void rejectsWithMessage(CheckedRunnable operation, String text) throws Exception {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            if (expected.getMessage() != null && expected.getMessage().contains(text)) {
                return;
            }
            throw new AssertionError("invalid ADB arguments failed for the wrong reason", expected);
        }
        throw new AssertionError("oversized ADB shell service accepted");
    }

    private static String repeated(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private static void verifySignature(String publicKeyPath, String tokenPath,
            String signaturePath) throws Exception {
        byte[] publicKeyBytes = Files.readAllBytes(new File(publicKeyPath).toPath());
        byte[] token = Files.readAllBytes(new File(tokenPath).toPath());
        byte[] signatureBytes = Files.readAllBytes(new File(signaturePath).toPath());
        RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(publicKeyBytes));
        if (signatureBytes.length != 256 || token.length != 20) {
            throw new AssertionError("unexpected ADB token or signature length");
        }
        BigInteger recovered = new BigInteger(1, signatureBytes).modPow(
                publicKey.getPublicExponent(), publicKey.getModulus());
        byte[] recoveredBytes = recovered.toByteArray();
        byte[] decoded = new byte[signatureBytes.length];
        int sourceStart = Math.max(0, recoveredBytes.length - decoded.length);
        int copied = recoveredBytes.length - sourceStart;
        System.arraycopy(recoveredBytes, sourceStart, decoded, decoded.length - copied, copied);

        byte[] digestInfoPrefix = new byte[] {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        };
        int paddingLength = signatureBytes.length - 3 - digestInfoPrefix.length - token.length;
        byte[] expected = new byte[signatureBytes.length];
        expected[0] = 0;
        expected[1] = 1;
        Arrays.fill(expected, 2, 2 + paddingLength, (byte) 0xff);
        expected[2 + paddingLength] = 0;
        System.arraycopy(digestInfoPrefix, 0, expected, 3 + paddingLength,
                digestInfoPrefix.length);
        System.arraycopy(token, 0, expected, 3 + paddingLength + digestInfoPrefix.length,
                token.length);
        if (!Arrays.equals(expected, decoded)) {
            throw new AssertionError("ADB token signature does not match the fixture public key");
        }
        System.out.println("PASS: ADB signature decodes to the SHA1 DigestInfo and challenge token");
    }

    private static void verifyUploadedKey(String publicKeyPath, String payloadPath)
            throws Exception {
        RSAPublicKey expected = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Files.readAllBytes(new File(publicKeyPath).toPath())));
        byte[] payload = Files.readAllBytes(new File(payloadPath).toPath());
        byte[] prefix = " ".getBytes("UTF-8");
        int separator = -1;
        for (int i = 0; i < payload.length; i++) {
            if (payload[i] == prefix[0]) {
                separator = i;
                break;
            }
        }
        if (separator < 0 || payload[payload.length - 1] != 0) {
            throw new AssertionError("malformed ADB RSA public key payload");
        }
        byte[] encoded = Base64.getDecoder().decode(Arrays.copyOfRange(payload, 0, separator));
        ByteBuffer key = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        if (encoded.length != 524 || key.getInt() != 64) {
            throw new AssertionError("unexpected ADB RSA public key structure");
        }
        key.getInt();
        byte[] modulusLittleEndian = new byte[256];
        key.get(modulusLittleEndian);
        byte[] modulusBigEndian = new byte[modulusLittleEndian.length];
        for (int i = 0; i < modulusLittleEndian.length; i++) {
            modulusBigEndian[i] = modulusLittleEndian[modulusLittleEndian.length - 1 - i];
        }
        BigInteger modulus = new BigInteger(1, modulusBigEndian);
        key.position(key.position() + 256);
        int exponent = key.getInt();
        if (!expected.getModulus().equals(modulus)
                || expected.getPublicExponent().intValue() != exponent) {
            throw new AssertionError("uploaded ADB public key differs from the generated fixture key");
        }
        System.out.println("PASS: uploaded ADB public key matches the fixture-generated key");
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
