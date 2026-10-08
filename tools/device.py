#!/usr/bin/env python3
"""Explicit, finite developer tests on one configured, already-authorized ADB device."""
import argparse
import datetime
import json
import pathlib
import shlex
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
REMOTE = "/data/local/tmp/vban-sender"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True, help="Exact ADB serial; no discovery or connect")
    parser.add_argument("action", choices=["inspect", "tone", "capture", "app-aware", "stop"])
    parser.add_argument("--destination", help="Receiver IPv4, read from its existing configuration")
    parser.add_argument("--port", type=int)
    parser.add_argument("--stream")
    parser.add_argument("--seconds", type=int, default=30)
    parser.add_argument("--packet-frames", type=int, default=256)
    args = parser.parse_args()
    adb = ["adb", "-s", args.device]

    def shell(command, timeout=20):
        return subprocess.run(adb + ["shell", command], check=True, capture_output=True,
                              text=True, timeout=timeout).stdout

    if args.action == "stop":
        print(shell(f"test -d {REMOTE} && touch {REMOTE}/stop"), end="")
        # No guessed PID or broad process kill. The finite proof also has a deadline.
        print("Stop requested. Confirm STOPPED in the capture command's output.")
        return

    if args.action == "inspect":
        facts = shell("getprop ro.build.version.sdk; getprop ro.build.version.release; "
                      "getprop ro.product.model; getprop ro.build.fingerprint; id")
        audio = shell("dumpsys audio")
        packages = {}
        for package in ["com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
                        "com.google.android.youtube.tv"]:
            result = shell("dumpsys package " + shlex.quote(package))
            packages[package] = [line.strip() for line in result.splitlines()
                                 if "versionName=" in line or "versionCode=" in line]
        report = {"device": args.device, "facts": facts, "packages": packages,
                  "audio": audio, "capture_gate": "NOT_RUN", "pilot_gate": "NOT_RUN",
                  "self_adb_boot_gate": "NOT_RUN"}
        directory = ROOT / "build" / "device"
        directory.mkdir(parents=True, exist_ok=True)
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        path = directory / f"inspect-{stamp}.json"
        path.write_text(json.dumps(report, indent=2) + "\n")
        print(facts, end="")
        print(f"Device evidence: {path}")
        return

    if not args.destination or args.port is None or not args.stream:
        parser.error("tone/capture require --destination, --port and --stream")
    artifact = ROOT / "build" / "vban-engine.jar"
    if not artifact.is_file():
        parser.error("Build first: sh tools/build.sh")
    if not 1 <= args.seconds <= 3600 or not 1 <= args.packet_frames <= 256:
        parser.error("seconds: 1..3600; packet-frames: 1..256")
    # Validate before pushing anything. Production Java validates the same wire inputs.
    import ipaddress
    destination = ipaddress.IPv4Address(args.destination)
    if destination.is_unspecified or destination.is_multicast or int(destination) == 0xffffffff:
        parser.error("destination must be a unicast IPv4")
    if not 1 <= args.port <= 65535 or not 1 <= len(args.stream) <= 16:
        parser.error("port: 1..65535; stream: 1..16 printable ASCII characters")
    if any(ord(char) < 32 or ord(char) > 126 for char in args.stream):
        parser.error("stream must contain printable ASCII characters")
    shell(f"mkdir -p {REMOTE} && chmod 700 {REMOTE}")
    subprocess.run(adb + ["push", str(artifact), f"{REMOTE}/engine.jar"], check=True)
    shell(f"chmod 600 {REMOTE}/engine.jar")
    entry = "ToneSender" if args.action == "tone" else "CaptureSender"
    command = ["app_process", "/", "app.vbansender." + entry, str(destination), str(args.port),
               args.stream, str(args.seconds), str(args.packet_frames)]
    if args.action == "app-aware":
        command.append("--app-aware")
    remote_command = f"CLASSPATH={REMOTE}/engine.jar " + shlex.join(command)
    directory = ROOT / "build" / "device"
    directory.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    path = directory / f"{args.action}-{stamp}.log"
    print(f"Finite {args.action} probe, {args.seconds}s. Log: {path}", flush=True)
    if args.action in ("capture", "app-aware"):
        print("Capture may redirect HDMI audio. Stop from another terminal using action 'stop'.", flush=True)
    with path.open("w") as log:
        process = subprocess.Popen(adb + ["shell", remote_command], stdout=log, stderr=subprocess.STDOUT)
        try:
            code = process.wait(timeout=args.seconds + 30)
        except (KeyboardInterrupt, subprocess.TimeoutExpired):
            try:
                if args.action in ("capture", "app-aware"):
                    shell(f"touch {REMOTE}/stop")
                    process.wait(timeout=10)
            finally:
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=5)
            raise
    print(path.read_text(), end="")
    if code:
        raise SystemExit(code)


if __name__ == "__main__":
    main()
