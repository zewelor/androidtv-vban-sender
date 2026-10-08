#!/usr/bin/env python3
"""Capture numeric PCM metrics and observe STREAM_MUSIC while the user presses the remote."""
import argparse
import datetime
import json
import pathlib
import subprocess
import sys
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--listen-ip", required=True)
    parser.add_argument("--seconds", type=int, default=60)
    args = parser.parse_args()
    if not 1 <= args.seconds <= 3600:
        parser.error("seconds must be in 1..3600")
    stop = threading.Event()
    started = time.monotonic()
    snapshots = []
    errors = []

    def observe():
        while not stop.is_set():
            try:
                result = subprocess.run(["adb", "-s", args.device, "shell", "dumpsys audio"],
                                        capture_output=True, text=True, check=True, timeout=5)
                lines = result.stdout.splitlines()
                begin = next(i for i, line in enumerate(lines) if line.strip() == "- STREAM_MUSIC:")
                end = next(i for i in range(begin + 1, len(lines))
                           if lines[i].strip().startswith("- STREAM_"))
                snapshots.append({"elapsed": round(time.monotonic() - started, 2),
                                  "music_state": "\n".join(lines[begin:end])})
            except Exception as error:
                errors.append(f"{type(error).__name__}: {error}")
            stop.wait(1)

    thread = threading.Thread(target=observe)
    thread.start()
    code = None
    try:
        code = subprocess.call([sys.executable, "-B", str(ROOT / "tools" / "capture-probe.py"),
                                "--device", args.device, "--listen-ip", args.listen_ip,
                                "--seconds", str(args.seconds)])
    finally:
        stop.set()
        thread.join()
        directory = ROOT / "build" / "device"
        directory.mkdir(parents=True, exist_ok=True)
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        path = directory / f"pilot-state-{stamp}.json"
        path.write_text(json.dumps({"snapshots": snapshots, "errors": errors,
                                   "capture_exit_code": code}, indent=2) + "\n")
        print(f"Pilot state evidence: {path}")
    if errors:
        raise RuntimeError(f"Volume observation failed: {errors}")
    raise SystemExit(code)


if __name__ == "__main__":
    main()
