#!/usr/bin/env python3
"""Run a finite, owned shell engine and retain numeric timing evidence locally."""
import argparse
import hashlib
import ipaddress
import json
import pathlib
import re
import shlex
import subprocess
import time
import uuid

REMOTE = "/data/local/tmp/vban-sender"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--jar", type=pathlib.Path, required=True)
    parser.add_argument("--receiver-ip", required=True)
    parser.add_argument("--port", type=int, default=6980)
    parser.add_argument("--stream", default="vban-audio")
    parser.add_argument("--app", required=True, help="expected foreground application")
    parser.add_argument("--seconds", type=int, default=60)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    if not args.jar.is_file():
        parser.error("jar must identify the exact built measurement artifact")
    destination = ipaddress.IPv4Address(args.receiver_ip)
    if destination.is_multicast or destination.is_unspecified or int(destination) == 0xffffffff:
        parser.error("receiver-ip must be a unicast IPv4")
    if not 1 <= args.seconds <= 3600 or not 1 <= args.port <= 65535:
        parser.error("seconds: 1..3600; port: 1..65535")
    if not 1 <= len(args.stream) <= 16 or any(ord(c) < 32 or ord(c) > 126 for c in args.stream):
        parser.error("stream must be 1..16 printable ASCII characters")
    if not re.fullmatch(r"[A-Za-z0-9_.]+", args.app):
        parser.error("app must be a package name")
    if args.output.exists():
        parser.error("output directory must be new, to preserve earlier evidence")
    adb = ["adb", "-s", args.device]

    def shell(command):
        return subprocess.check_output(adb + ["shell", command], text=True, timeout=15).strip()

    def no_engine():
        processes = shell("ps -A -o ARGS")
        if any("app.vbansender.AudioEngine" in line for line in processes.splitlines()):
            raise RuntimeError("A VBAN engine is already running; select OFF before measuring")

    no_engine()
    activities = shell("dumpsys activity activities")
    resumed = [line.strip() for line in activities.splitlines() if "mResumedActivity:" in line]
    if not any(args.app + "/" in line for line in resumed):
        raise RuntimeError("Expected foreground app is absent: " + args.app)
    original_surround = shell("settings get global encoded_surround_output")
    if not re.fullmatch(r"null|[0-3]", original_surround):
        raise RuntimeError("Unexpected original surround setting: " + original_surround)
    original_stop = shell("if [ -e " + REMOTE + "/stop ]; then echo PRESENT; else echo ABSENT; fi")
    args.output.mkdir(parents=True, mode=0o700)
    run = uuid.uuid4().hex
    marker = REMOTE + "/enabled-" + run
    remote_jar = "/data/local/tmp/vban-measure-" + run + ".jar"
    started_epoch = int(shell("date +%s"))
    report = {"device": args.device, "app": args.app, "run": run,
              "jar_sha256": hashlib.sha256(args.jar.read_bytes()).hexdigest(),
              "seconds": args.seconds, "original_surround": original_surround,
              "scope": "Finite real Android capture; no physical listening or A/V sync proof"}
    process = None
    try:
        subprocess.run(adb + ["push", str(args.jar), remote_jar], check=True,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
        installed_hash = shell("sha256sum " + shlex.quote(remote_jar)).split()[0]
        if installed_hash != report["jar_sha256"]:
            raise RuntimeError("Device measurement JAR hash differs from the local artifact")
        shell("mkdir -p " + REMOTE + " && chmod 700 " + REMOTE)
        shell("settings put global encoded_surround_output 1")
        shell("touch " + marker)
        command = ["app_process", "/", "app.vbansender.AudioEngine", str(destination),
                   str(args.port), args.stream, "256", run,
                   "--measure-seconds", str(args.seconds)]
        process = subprocess.Popen(adb + ["shell", "CLASSPATH=" + shlex.quote(remote_jar)
                                          + " " + shlex.join(command)],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        stdout, stderr = process.communicate(timeout=args.seconds + 20)
        (args.output / "engine.log").write_text(stdout + stderr)
        if process.returncode:
            raise RuntimeError("Diagnostic engine failed; see " + str(args.output / "engine.log"))
        final_state = shell("cat " + REMOTE + "/engine-state.txt")
        if "run=" + run + " " not in final_state or " state=STOPPED " not in final_state:
            raise RuntimeError("Finite engine did not publish its own STOPPED state: " + final_state)
        no_engine()
        metrics = [json.loads(line) for line in stdout.splitlines() if line.startswith('{"state":')]
        summaries = [item for item in metrics if item["state"] == "CAPTURE_ENDED"]
        if not summaries:
            raise RuntimeError("No capture session completed; expected app may be idle or unsupported")
        report.update({"result": "PASS_CAPTURE_TIMING", "sessions": summaries,
                       "nonzero_pcm_observed": any(item.get("peak", 0) > 0 for item in metrics),
                       "final_state": final_state})
        logs = shell("logcat -d -v epoch -s AudioFlinger:W MPB_DRM:W AndroidRuntime:E")
        relevant = []
        for line in logs.splitlines():
            if not any(text in line for text in ("RecordThread: buffer overflow", "Audio underrun",
                                                 "Illegal output device type 25")):
                continue
            match = re.match(r"\s*(\d+\.\d+)\s", line)
            if match and float(match[1]) >= started_epoch:
                relevant.append(line)
        report["audio_errors"] = relevant
    finally:
        # The unique marker fences this diagnostic engine even if ADB startup was delayed.
        shell("rm -f " + marker)
        if process and process.poll() is None:
            try:
                process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.communicate(timeout=3)
        no_engine()
        shell("settings delete global encoded_surround_output" if original_surround == "null"
              else "settings put global encoded_surround_output " + original_surround)
        if original_stop == "PRESENT":
            shell("touch " + REMOTE + "/stop")
        shell("rm -f " + remote_jar)
        restored = shell("settings get global encoded_surround_output")
        if restored != original_surround:
            raise RuntimeError("Original audio setting was not restored")
        report["restored_surround"] = restored
        report["finished_epoch"] = int(time.time())
        (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
