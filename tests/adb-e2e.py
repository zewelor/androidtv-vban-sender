#!/usr/bin/env python3
"""Loopback-only fake ADB peer for the local Java shell adapter."""
import base64
import json
import os
import pathlib
import re
import select
import shlex
import socket
import struct
import subprocess
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
KEYS = ROOT / "build" / "adb-fixture" / "keys"
EVIDENCE = ROOT / "build" / "adb-fixture" / "evidence"
REPORT = ROOT / "build" / "e2e" / "adb" / "report.json"
JAVA = ["java", "-cp", os.environ.get(
    "VBAN_SENDER_ADB_TEST_CLASSPATH",
    str(ROOT / "build/classes") + os.pathsep + str(ROOT / "build/test-classes"))]
HARNESS = JAVA + ["app.vbansender.AdbClientHarness"]
CHECK = JAVA + ["com.cgutman.adblib.AdbClientCheck"]

CMD_CNXN = int.from_bytes(b"CNXN", "little")
CMD_AUTH = int.from_bytes(b"AUTH", "little")
CMD_OPEN = int.from_bytes(b"OPEN", "little")
CMD_OKAY = int.from_bytes(b"OKAY", "little")
CMD_CLSE = int.from_bytes(b"CLSE", "little")
CMD_WRTE = int.from_bytes(b"WRTE", "little")
AUTH_TOKEN = 1
AUTH_SIGNATURE = 2
AUTH_RSA_PUBLIC = 3
TOKEN = bytes(range(20))
REMOTE_ID = 77


def recv_exact(sock, size):
    chunks = bytearray()
    while len(chunks) < size:
        chunk = sock.recv(size - len(chunks))
        if not chunk:
            raise EOFError("ADB socket closed during packet")
        chunks.extend(chunk)
    return bytes(chunks)


def recv_packet(sock):
    header = recv_exact(sock, 24)
    command, arg0, arg1, size, checksum, magic = struct.unpack("<6I", header)
    assert magic == (command ^ 0xffffffff), "bad ADB packet magic"
    assert size <= 1024 * 1024, "client sent an oversized ADB packet"
    payload = recv_exact(sock, size) if size else b""
    assert sum(payload) & 0xffffffff == checksum, "bad ADB payload checksum"
    return command, arg0, arg1, payload


def send_packet(sock, command, arg0=0, arg1=0, payload=b""):
    checksum = sum(payload) & 0xffffffff
    sock.sendall(struct.pack("<6I", command, arg0, arg1, len(payload), checksum,
                             command ^ 0xffffffff) + payload)


def send_malformed_length(sock, length):
    sock.sendall(struct.pack("<6I", CMD_WRTE, 1, 2, length, 0,
                             CMD_WRTE ^ 0xffffffff))


class FakeAdbPeer:
    def __init__(self, scenario):
        self.scenario = scenario
        self.server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server.bind(("127.0.0.1", 0))
        self.server.listen(1)
        self.server.settimeout(5)
        self.port = self.server.getsockname()[1]
        self.error = None
        self.client_address = None
        self.uploaded_key = None
        self.token = None
        self.signature = None
        self.command = None
        self.thread = threading.Thread(target=self.serve, name="fake-adb-peer")

    def start(self):
        self.thread.start()

    def join(self):
        self.thread.join(timeout=6)
        if self.thread.is_alive():
            raise AssertionError("fake ADB peer did not finish")
        if self.error is not None:
            raise self.error
        if self.client_address is not None:
            assert self.client_address[0] == "127.0.0.1", "adapter did not connect via IPv4 loopback"
        self.server.close()

    def serve(self):
        try:
            conn, self.client_address = self.server.accept()
            conn.settimeout(4)
            with conn:
                packet = recv_packet(conn)
                assert packet[0] == CMD_CNXN, "client did not begin with CNXN"
                if self.scenario == "malformed":
                    send_malformed_length(conn, 0x7fffffff)
                    return

                # OPEN/read deadline cases do not test RSA authorization; avoid
                # consuming their short deadline in a cold cryptographic provider.
                if self.scenario not in ("stalled-open", "stalled-read"):
                    send_packet(conn, CMD_AUTH, AUTH_TOKEN, 0, TOKEN)
                    auth = recv_packet(conn)
                    assert auth[0] == CMD_AUTH and auth[1] == AUTH_SIGNATURE
                    assert len(auth[3]) == 256, "ADB RSA signature has the wrong length"
                    self.token = TOKEN
                    self.signature = auth[3]

                    if self.scenario == "new-key":
                        send_packet(conn, CMD_AUTH, AUTH_TOKEN, 0, TOKEN)
                        public_key = recv_packet(conn)
                        assert public_key[0] == CMD_AUTH and public_key[1] == AUTH_RSA_PUBLIC
                        self.uploaded_key = public_key[3]
                        assert self.uploaded_key.endswith(b" unknown@unknown\0")
                    elif self.scenario == "stalled-auth":
                        while conn.recv(1):
                            pass
                        return

                send_packet(conn, CMD_CNXN, 0x01000000, 4096, b"device::\0")
                if self.scenario == "malformed-after-connect":
                    send_malformed_length(conn, 0x7fffffff)
                    return

                opened = recv_packet(conn)
                assert opened[0] == CMD_OPEN, "client did not open shell service"
                local_id = opened[1]
                service = opened[3].rstrip(b"\0").decode("utf-8")
                assert service.startswith("shell:"), "adapter did not request the classic shell service"
                self.command = service[len("shell:"):]
                words = shlex.split(self.command)
                assert words[:2] == ["/system/bin/sh", "-c"]
                expected_command = (
                    "printf 'fixture-output'; # UTF-8: café ☕\n"
                    "CLASSPATH='apk' nohup setsid app_process /system/bin/app.vbansender.ProofService "
                    "</dev/null >log 2>&1 & wait_status=$?; exit $wait_status")
                assert words[2].rstrip(";") == expected_command
                assert words[3].rstrip(";") == "__vban_sender_status=$?"
                assert words[4] == "printf" and words[6] == "$__vban_sender_status"
                if self.scenario == "stalled-open":
                    while conn.recv(1):
                        pass
                    return
                send_packet(conn, CMD_OKAY, REMOTE_ID, local_id)
                readable, _, _ = select.select([conn], [], [], 0.05)
                assert not readable, "one-shot shell sent interactive stdin data"
                found = re.search(r"(__VBAN_SENDER_ADB_[0-9a-f]{32}__)", self.command)
                assert found, "shell command has no random completion nonce"
                marker = found.group(1).encode("ascii")

                if self.scenario == "stalled-read":
                    while conn.recv(1):
                        pass
                    return
                if self.scenario == "early-eof":
                    send_packet(conn, CMD_CLSE, REMOTE_ID, local_id)
                    return
                if self.scenario in ("output-limit", "output-boundary"):
                    remaining = 17 * 4096 if self.scenario == "output-limit" else 16 * 4096
                    while remaining:
                        chunk = b"x" * min(4096, remaining)
                        send_packet(conn, CMD_WRTE, REMOTE_ID, local_id, chunk)
                        remaining -= len(chunk)
                        try:
                            ack = recv_packet(conn)
                        except (EOFError, OSError):
                            return
                        assert ack[0] == CMD_OKAY and ack[1] == local_id
                    if self.scenario == "output-boundary":
                        trailer = b"\n" + marker + b":0\n"
                        send_packet(conn, CMD_WRTE, REMOTE_ID, local_id, trailer)
                        ack = recv_packet(conn)
                        assert ack[0] == CMD_OKAY and ack[1] == local_id
                        send_packet(conn, CMD_CLSE, REMOTE_ID, local_id)
                    return

                exit_code = 7 if self.scenario == "nonzero" else 0
                payload = b"fixture-output\n" + marker + b":" + str(exit_code).encode() + b"\n"
                send_packet(conn, CMD_WRTE, REMOTE_ID, local_id, payload)
                send_packet(conn, CMD_CLSE, REMOTE_ID, local_id)
        except (EOFError, OSError) as e:
            if self.scenario not in ("stalled-auth", "output-limit", "output-boundary"):
                self.error = e
        except Exception as e:
            self.error = e


def run_harness(peer, timeout_ms=1800):
    process = subprocess.run(HARNESS + ["shell", str(peer.port), str(timeout_ms), str(KEYS)],
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=6)
    peer.join()
    return process


def verify_signature(peer, name):
    token_path = EVIDENCE / (name + "-token.bin")
    signature_path = EVIDENCE / (name + "-signature.bin")
    token_path.write_bytes(peer.token)
    signature_path.write_bytes(peer.signature)
    result = subprocess.run(CHECK + ["verify-signature", str(KEYS / "adb-public.der"),
                                     str(token_path), str(signature_path)],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=5)
    assert result.returncode == 0, result.stderr


def successful_auth_and_shell(scenario):
    peer = FakeAdbPeer(scenario)
    peer.start()
    result = run_harness(peer)
    assert result.returncode == 0, result.stderr
    assert "fixture-output" in result.stdout and "HARNESS_SUCCESS" in result.stdout
    verify_signature(peer, scenario)
    return {"result": "PASS", "peer": "127.0.0.1", "auth": scenario}


def expected_error(scenario, timeout_ms=1800):
    peer = FakeAdbPeer(scenario)
    peer.start()
    started = time.monotonic()
    result = run_harness(peer, timeout_ms)
    elapsed = time.monotonic() - started
    if scenario == "output-boundary":
        assert result.returncode == 0, result.stderr
        assert len(result.stdout.split("\nHARNESS_SUCCESS", 1)[0]) == 65536
    else:
        assert result.returncode != 0, "expected fake-peer failure was accepted"
    if scenario == "early-eof":
        assert "closed before its completion marker" in result.stderr, result.stderr
    elif scenario == "nonzero":
        assert "HARNESS_COMMAND_FAILED exit=7" in result.stderr, result.stderr
        output = base64.b64decode(result.stderr.split(" output=", 1)[1].strip()).decode("utf-8")
        assert output == "fixture-output"
    elif scenario == "output-limit":
        assert "output exceeded 65536 bytes" in result.stderr, result.stderr
    elif scenario in ("stalled-auth", "stalled-open", "stalled-read"):
        assert "SocketTimeoutException" in result.stderr, result.stderr
        if scenario in ("stalled-open", "stalled-read"):
            assert peer.command is not None, "timeout occurred before the intended shell phase"
        assert elapsed < 2.5, "deadline did not close the stalled ADB socket"
    elif scenario in ("malformed", "malformed-after-connect"):
        assert elapsed < 2.5, "malformed ADB payload did not fail promptly"
    return {"result": "PASS", "elapsed_seconds": round(elapsed, 3)}


def main():
    KEYS.mkdir(parents=True, exist_ok=True)
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    created = subprocess.run(HARNESS + ["fixture", str(KEYS)], stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, text=True, timeout=10)
    assert created.returncode == 0, created.stderr
    assert "FIXTURE_KEYS_CREATED" in created.stdout

    report = {
        "result": "PASS",
        "scope": "Java ADB client over loopback to a synthetic TCP peer; no device connection",
        "new_key_approval_and_shell": successful_auth_and_shell("new-key"),
        "known_key_reconnect": successful_auth_and_shell("known-key"),
        "early_eof": expected_error("early-eof"),
        "nonzero_exit": expected_error("nonzero"),
        "output_limit": expected_error("output-limit"),
        "output_boundary": expected_error("output-boundary"),
        "stalled_auth_deadline": expected_error("stalled-auth", timeout_ms=350),
        "stalled_open_deadline": expected_error("stalled-open", timeout_ms=350),
        "stalled_read_deadline": expected_error("stalled-read", timeout_ms=350),
        "malformed_payload_before_cnxn": expected_error("malformed"),
        "malformed_payload_after_cnxn": expected_error("malformed-after-connect"),
    }

    public_key_file = EVIDENCE / "uploaded-public-key.bin"
    peer = FakeAdbPeer("new-key")
    peer.start()
    result = run_harness(peer)
    assert result.returncode == 0, result.stderr
    public_key_file.write_bytes(peer.uploaded_key)
    verified = subprocess.run(CHECK + ["verify-upload", str(KEYS / "adb-public.der"),
                                       str(public_key_file)],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=5)
    assert verified.returncode == 0, verified.stderr
    report["uploaded_key_matches_fixture"] = {"result": "PASS"}

    REPORT.write_text(json.dumps(report, indent=2) + "\n")
    print("PASS: first-key approval, known-key signature, shell marker/status, EOF, output bound, deadline, malformed packets")


if __name__ == "__main__":
    main()
