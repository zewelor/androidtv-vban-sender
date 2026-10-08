#!/usr/bin/env python3
"""Receive a finite Android capture on this computer and keep only numeric audio metrics."""
import argparse
import datetime
import json
import math
import pathlib
import socket
import struct
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--listen-ip", required=True, help="This computer's LAN IPv4 reachable from the box")
    parser.add_argument("--seconds", type=int, default=10)
    args = parser.parse_args()
    if not 1 <= args.seconds <= 3600:
        parser.error("seconds must be in 1..3600")
    directory = ROOT / "build" / "device"
    directory.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    log_path = directory / f"probe-{stamp}.log"
    report_path = directory / f"probe-{stamp}.json"
    packets = 0
    frames = 0
    gaps = 0
    previous = None
    peers = set()
    buckets = {}
    started = time.monotonic()
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as receiver, log_path.open("w") as log:
        receiver.bind((args.listen_ip, 0))
        receiver.settimeout(0.2)
        command = [sys.executable, "-B", str(ROOT / "tools" / "device.py"),
                   "--device", args.device, "capture", "--destination", args.listen_ip,
                   "--port", str(receiver.getsockname()[1]), "--stream", "VBANTX",
                   "--seconds", str(args.seconds)]
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
        try:
            while time.monotonic() - started < args.seconds + 25:
                try:
                    wire, peer = receiver.recvfrom(2048)
                except socket.timeout:
                    if process.poll() is not None:
                        break
                    continue
                peers.add(peer[0])
                if (len(wire) < 32 or wire[:5] != b"VBAN\x03" or wire[6:8] != b"\x01\x01"
                        or wire[8:24] != b"VBANTX".ljust(16, b"\0")
                        or len(wire) != 28 + (wire[5] + 1) * 4):
                    raise RuntimeError("Invalid VBAN packet received")
                counter = struct.unpack_from("<I", wire, 24)[0]
                if previous is not None:
                    difference = (counter - previous) & 0xffffffff
                    if difference == 0 or difference > 0x7fffffff:
                        raise RuntimeError("Duplicate or out-of-order packet")
                    gaps += difference - 1
                previous = counter
                packets += 1
                frames += wire[5] + 1
                second = int(time.monotonic() - started)
                bucket = buckets.setdefault(second, {"frames": 0, "peak_l": 0, "peak_r": 0,
                                                     "sum_square_l": 0, "sum_square_r": 0})
                for left, right in struct.iter_unpack("<hh", wire[28:]):
                    bucket["frames"] += 1
                    bucket["peak_l"] = max(bucket["peak_l"], abs(left))
                    bucket["peak_r"] = max(bucket["peak_r"], abs(right))
                    bucket["sum_square_l"] += left * left
                    bucket["sum_square_r"] += right * right
            process.wait(timeout=2)
            if process.returncode:
                raise RuntimeError(f"Capture failed; see {log_path}")
            if packets == 0:
                raise RuntimeError(f"No packets received; see {log_path}")
        except BaseException:
            try:
                subprocess.run([sys.executable, "-B", str(ROOT / "tools" / "device.py"),
                                "--device", args.device, "stop"], timeout=25, check=False)
            finally:
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    process.wait(timeout=5)
            raise
    for bucket in buckets.values():
        bucket["rms_l"] = round(math.sqrt(bucket.pop("sum_square_l") / bucket["frames"]), 2)
        bucket["rms_r"] = round(math.sqrt(bucket.pop("sum_square_r") / bucket["frames"]), 2)
    report = {"result": "PASS_CAPTURE_TRANSPORT", "device": args.device, "peers": sorted(peers),
              "packets": packets, "frames": frames, "sequence_gaps": gaps,
              "seconds": args.seconds, "buckets": buckets,
              "scope": "Capture and UDP observed; no raw PCM saved and no loudspeaker playback",
              "pilot_gate": "NOT_RUN", "hdmi_return_gate": "REQUIRES_USER_OBSERVATION"}
    report_path.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    print(f"Evidence: {report_path}")


if __name__ == "__main__":
    main()
