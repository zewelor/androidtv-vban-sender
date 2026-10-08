#!/usr/bin/env python3
"""Synthetic HAL bursts -> production packet pacing -> actual UDP. No media content."""
import json
import os
import pathlib
import socket
import statistics
import struct
import subprocess
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
CLASSES = os.environ.get("PACING_CLASSES", "build/classes:build/test-classes")
OUTPUT = ROOT / "build" / "e2e" / "pacing"


def run(frames, scenario, period, blocks):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as receiver:
        receiver.bind(("127.0.0.1", 0))
        receiver.settimeout(0.1)
        process = subprocess.Popen(["java", "-cp", CLASSES, "app.vbansender.CapturePacketsHarness",
                                    str(receiver.getsockname()[1]), str(frames), scenario,
                                    str(period), str(blocks)], stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE)
        arrivals, sizes, samples = [], [], []
        deadline = time.monotonic() + blocks * period / 1e9 + 5
        try:
            while time.monotonic() < deadline:
                try:
                    packet, _ = receiver.recvfrom(2048)
                except socket.timeout:
                    if process.poll() is not None:
                        break
                    continue
                arrivals.append(time.monotonic_ns())
                assert packet[:5] == b"VBAN\x03" and packet[6:8] == b"\x01\x01"
                assert packet[8:24] == b"PACE-CHECK".ljust(16, b"\0")
                assert struct.unpack_from("<I", packet, 24)[0] == len(sizes), "sequence gap"
                count = packet[5] + 1
                assert len(packet) == 28 + count * 4
                sizes.append(count)
                samples.extend(struct.iter_unpack("<hh", packet[28:]))
            out, err = process.communicate(timeout=2)
            assert process.returncode == 0, err.decode()
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
    expected = frames if scenario.startswith("stop") else blocks * (513 if scenario == "partial" else 1024)
    assert len(samples) == expected, (scenario, len(samples), expected)
    for i, (left, right) in enumerate(samples):
        assert left == i & 0x7fff and right == ~(i & 0x7fff), "PCM changed"
    result = json.loads(out)
    assert result["packets"] == len(sizes)
    gaps = [(b - a) / 1e6 for a, b in zip(arrivals, arrivals[1:])]
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / f"{scenario}-{frames}.json").write_text(json.dumps(
        {"iat_ms": gaps, "packet_frames": sizes}, indent=2) + "\n")
    if scenario.startswith("stop"):
        assert len(sizes) == 1, "Stop must discard the rest of the current block"
        if scenario == "stop-wait":
            assert 0 < result["stop_latency_ns"] < 20000000, "Stop was held by the pacing wait"
    elif scenario == "stall":
        assert gaps[0] >= 40, "fixture must actually stall"
        assert min(gaps[1:]) > 2, "Do not catch up with a burst after a scheduler stall"
    elif scenario == "partial":
        assert sizes == [256, 256, 1], "partial capture tail must not be padded/lost"
        assert min(gaps) > 2, "partial blocks also need pacing"
    else:
        stride = 1024 // frames
        # The source chooses the first send of each new block; only the packets
        # inside the read are paced. A late source read can shorten a block boundary.
        inner_gaps = sorted(gap for i, gap in enumerate(gaps) if (i + 1) % stride)
        assert inner_gaps[len(inner_gaps) // 20] > frames / 48000 * 1000 * 0.45, \
            ("HAL block was emitted as an unpaced UDP burst", scenario, frames,
             inner_gaps[len(inner_gaps) // 20], statistics.median(gaps))
        # Block starts follow the source clock, including a deliberately exaggerated ±2%.
        expected_span = (blocks - 1) * period / 1e9
        actual_span = (arrivals[(blocks - 1) * stride] - arrivals[0]) / 1e9
        assert abs(actual_span - expected_span) < 0.06, (
            "pacing accumulated source-clock drift", scenario, frames,
            actual_span, expected_span)
    return {"scenario": scenario, "packet_frames": frames, "source_period_ns": period,
            "blocks": blocks, "packets": len(sizes), "pcm_frames": len(samples),
            "iat_median_ms": statistics.median(gaps) if gaps else None,
            "iat_max_ms": max(gaps) if gaps else None}


def main():
    cases = [(256, "normal", 21333333, 120), (128, "normal", 21333333, 120),
             (256, "fast-clock", 20906667, 240), (256, "slow-clock", 21760000, 240),
             (256, "partial", 21333333, 1), (256, "stop", 21333333, 1),
             (256, "stall", 21333333, 1), (256, "stop-wait", 21333333, 1)]
    report = {"result": "PASS", "scope": "Synthetic source, real production packet path and UDP",
              "scenarios": [run(*case) for case in cases]}
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
