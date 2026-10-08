#!/usr/bin/env python3
"""Run the real synthetic sender against a real UDP socket; retain a WAV and JSON."""
import json
import math
import pathlib
import socket
import struct
import subprocess
import time
import wave

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build" / "e2e"
JAVA = ["java", "-cp", str(ROOT / "build" / "classes"), "app.vbansender.ToneSender"]


def scenario(frames, stream):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as receiver:
        receiver.bind(("127.0.0.1", 0))
        receiver.settimeout(0.2)
        args = ["127.0.0.1", str(receiver.getsockname()[1]), stream, "1", str(frames)]
        process = subprocess.Popen(JAVA + args, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        chunks = []
        arrivals = []
        deadline = time.monotonic() + 8
        try:
            while time.monotonic() < deadline:
                try:
                    data, _ = receiver.recvfrom(2048)
                except socket.timeout:
                    if process.poll() is not None:
                        break
                    continue
                assert data[:4] == b"VBAN", "signature"
                assert data[4] == 3 and data[6:8] == b"\x01\x01", "PCM 48k stereo S16LE"
                count = data[5] + 1
                assert count == frames and len(data) == 28 + count * 4, "packet framing"
                assert data[8:24] == stream.encode("ascii").ljust(16, b"\0"), "stream name"
                assert struct.unpack_from("<I", data, 24)[0] == len(chunks), "packet sequence"
                chunks.append(data[28:])
                arrivals.append(time.monotonic())
            stdout, stderr = process.communicate(timeout=2)
            assert process.returncode == 0, stderr.decode()
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
        expected_frames = 48000 // frames * frames
        pcm = b"".join(chunks)
        assert len(pcm) == expected_frames * 4, "finite run must deliver all frames on loopback"
        left = []
        right = []
        for i, (l_sample, r_sample) in enumerate(struct.iter_unpack("<hh", pcm)):
            assert abs(l_sample - round(1000 * math.sin(2 * math.pi * 440 * i / 48000))) <= 1
            assert abs(r_sample - round(1000 * math.sin(2 * math.pi * 880 * i / 48000))) <= 1
            left.append(l_sample)
            right.append(r_sample)
        assert max(map(abs, left + right)) <= 1000, "quiet tone amplitude"
        assert 0.5 < arrivals[-1] - arrivals[0] < 3, "sender must pace live audio"
        path = OUTPUT / f"tone-{frames}.wav"
        with wave.open(str(path), "wb") as artifact:
            artifact.setnchannels(2)
            artifact.setsampwidth(2)
            artifact.setframerate(48000)
            artifact.writeframes(pcm)
        return {"packet_frames": frames, "packets": len(chunks), "frames": expected_frames,
                "wav": path.name, "sender": json.loads(stdout)}


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    results = [scenario(256, "VBANTX"), scenario(128, "1234567890123456")]
    for args in [[], ["invalid-host", "6980", "VBANTX", "1", "256"],
                 ["127.0.0.1", "0", "VBANTX", "1", "256"],
                 ["127.0.0.1", "6980", "audio-\u2603", "1", "256"],
                 ["127.0.0.1", "6980", "VBANTX", "0", "256"],
                 ["127.0.0.1", "6980", "VBANTX", "1", "257"]]:
        result = subprocess.run(JAVA + args, capture_output=True, timeout=3)
        assert result.returncode != 0 and result.stderr, f"invalid CLI accepted: {args}"
    report = {"result": "PASS", "scenarios": results, "invalid_cli_cases": 6,
              "scope": "Real JVM sender and UDP on loopback; no Android capture or PipeWire proof"}
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
