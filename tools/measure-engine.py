#!/usr/bin/env python3
"""Run a finite, owned shell engine and retain numeric timing evidence locally."""
import argparse
import hashlib
import ipaddress
import json
import os
import pathlib
import re
import shlex
import subprocess
import sys
import time
import uuid

REMOTE = "/data/local/tmp/vban-sender"
REPORT_SCHEMA = 1
CLEANUP_STEPS = ("marker_remove", "process_client", "engine_absence",
                 "stop_restore", "surround_restore", "jar_remove")
STEP_STATUSES = {"pending", "running", "done", "failed", "blocked"}
CAPTURE_RESULTS = {"RUNNING", "PASS_CAPTURE_TIMING", "CAPTURE_FAILED", "CAPTURE_INTERRUPTED"}


def atomic_write(path, report):
    """Replace a JSON report durably so every remote mutation has a recovery record."""
    path = pathlib.Path(path)
    temporary = path.with_name(path.name + ".tmp-" + uuid.uuid4().hex)
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    fd = os.open(temporary, flags, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            output.write(json.dumps(report, indent=2) + "\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        directory_fd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    except BaseException:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass
        raise


def read_cleanup_report(path, device):
    try:
        report = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("Cannot read cleanup report: " + str(error)) from error
    if not isinstance(report, dict) or report.get("schema") != REPORT_SCHEMA:
        raise ValueError("Unsupported cleanup report schema")
    if report.get("device") != device:
        raise ValueError("Cleanup report belongs to a different device")
    run = report.get("run")
    digest = report.get("jar_sha256")
    if not isinstance(run, str) or not re.fullmatch(r"[0-9a-f]{32}", run):
        raise ValueError("Invalid run identity in cleanup report")
    if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("Invalid JAR hash in cleanup report")
    expected_marker = REMOTE + "/enabled-" + run
    expected_jar = "/data/local/tmp/vban-measure-" + run + "-" + digest[:12] + ".jar"
    if report.get("marker_path") != expected_marker or report.get("jar_path") != expected_jar:
        raise ValueError("Cleanup report contains paths outside its owned run")
    if report.get("original_surround") not in ("null", "0", "1", "2", "3"):
        raise ValueError("Invalid original surround setting in cleanup report")
    if report.get("original_stop") not in ("PRESENT", "ABSENT"):
        raise ValueError("Invalid original stop marker state in cleanup report")
    if report.get("capture_result") not in CAPTURE_RESULTS:
        raise ValueError("Invalid capture result in cleanup report")
    cleanup = report.get("cleanup")
    if not isinstance(cleanup, dict) or set(cleanup) != set(CLEANUP_STEPS):
        raise ValueError("Invalid cleanup steps in report")
    for name, step in cleanup.items():
        if not isinstance(step, dict) or step.get("status") not in STEP_STATUSES:
            raise ValueError("Invalid status for cleanup step " + name)
        attempts = step.get("attempts")
        if not isinstance(attempts, list):
            raise ValueError("Invalid attempts for cleanup step " + name)
        for attempt in attempts:
            if (not isinstance(attempt, dict)
                    or not isinstance(attempt.get("number"), int)
                    or attempt["number"] < 1
                    or attempt.get("status") not in ("running", "ok", "error")
                    or not isinstance(attempt.get("started_epoch"), int)):
                raise ValueError("Invalid attempt record for cleanup step " + name)
    if not isinstance(report.get("primary_error"), (str, type(None))):
        raise ValueError("Invalid primary error in cleanup report")
    return report


def initial_report(device, app, run, digest, seconds, surround, stop, started_epoch):
    return {
        "schema": REPORT_SCHEMA,
        "device": device,
        "app": app,
        "run": run,
        "jar_sha256": digest,
        "seconds": seconds,
        "marker_path": REMOTE + "/enabled-" + run,
        "jar_path": "/data/local/tmp/vban-measure-" + run + "-" + digest[:12] + ".jar",
        "original_surround": surround,
        "original_stop": stop,
        "started_epoch": started_epoch,
        "scope": "Finite real Android capture; no physical listening or A/V sync proof",
        "capture_result": "RUNNING",
        "result": "RUNNING",
        "primary_error": None,
        "cleanup": {name: {"status": "pending", "attempts": [], "error": None}
                    for name in CLEANUP_STEPS},
    }


def format_error(error):
    message = str(error).strip()
    return type(error).__name__ + (": " + message if message else "")


def record_blocked(report, name, reason, save):
    step = report["cleanup"][name]
    if step["status"] == "done":
        return
    step["status"] = "blocked"
    step["error"] = reason
    save()


def run_cleanup(report, save, adb, process=None, log_path=None):
    def shell(command):
        return subprocess.check_output(adb + ["shell", command], text=True,
                                       timeout=15).strip()

    def record_log(stdout="", stderr=""):
        if log_path is not None and (stdout or stderr):
            pathlib.Path(log_path).write_text(stdout + stderr, encoding="utf-8")

    def step(name, action, refresh=False):
        state = report["cleanup"][name]
        if state["status"] == "done" and not refresh:
            return True
        state["status"] = "pending"
        state["error"] = None
        for attempt_number in range(1, 3):
            attempt = {"number": len(state["attempts"]) + 1,
                       "started_epoch": int(time.time()), "status": "running"}
            state["attempts"].append(attempt)
            save()
            try:
                action()
                attempt["status"] = "ok"
                state["status"] = "done"
                state["error"] = None
                save()
                return True
            except Exception as error:
                attempt["status"] = "error"
                attempt["error"] = format_error(error)
                state["error"] = format_error(error)
                state["status"] = "failed" if attempt_number == 2 else "pending"
                save()
                if attempt_number == 1:
                    time.sleep(0.2)
        return False

    def remove_marker():
        state = shell("if [ -e " + shlex.quote(report["marker_path"])
                      + " ]; then echo PRESENT; else echo ABSENT; fi")
        if state == "ABSENT":
            return
        shell("rm -f " + shlex.quote(report["marker_path"]))
        state = shell("if [ -e " + shlex.quote(report["marker_path"])
                      + " ]; then echo PRESENT; else echo ABSENT; fi")
        if state != "ABSENT":
            raise RuntimeError("Owned startup marker remains present")

    marker_fenced = step("marker_remove", remove_marker, refresh=True)

    def stop_local_client():
        if process is None:
            return
        if process.poll() is None:
            try:
                stdout, stderr = process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                process.terminate()
                stdout, stderr = process.communicate(timeout=3)
            record_log(stdout or "", stderr or "")
        else:
            stdout, stderr = process.communicate()
            record_log(stdout or "", stderr or "")

    step("process_client", stop_local_client)

    def ensure_no_engine():
        if not marker_fenced:
            raise RuntimeError("Owned startup marker removal has not been confirmed")
        processes = shell("ps -A -o ARGS")
        owners = [line.strip() for line in processes.splitlines()
                  if "app.vbansender.AudioEngine" in line
                  or "app.vbansender.CaptureSender" in line]
        if owners:
            raise RuntimeError("Capture engine is still present: " + " | ".join(owners))

    engine_absent = step("engine_absence", ensure_no_engine, refresh=True)
    if not marker_fenced or not engine_absent:
        record_blocked(report, "stop_restore", "Requires marker fence and capture-engine absence", save)
        record_blocked(report, "surround_restore", "Requires marker fence and capture-engine absence", save)
        record_blocked(report, "jar_remove", "Requires marker fence and capture-engine absence", save)
        return

    def restore_stop():
        state = shell("if [ -e " + shlex.quote(REMOTE + "/stop")
                      + " ]; then echo PRESENT; else echo ABSENT; fi")
        if state != report["original_stop"]:
            if report["original_stop"] == "ABSENT":
                shell("rm -f " + shlex.quote(REMOTE + "/stop"))
            else:
                shell("touch " + shlex.quote(REMOTE + "/stop"))
        state = shell("if [ -e " + shlex.quote(REMOTE + "/stop")
                      + " ]; then echo PRESENT; else echo ABSENT; fi")
        if state != report["original_stop"]:
            raise RuntimeError("Original stop marker state was not restored")

    step("stop_restore", restore_stop)

    def restore_surround():
        current = shell("settings get global encoded_surround_output")
        if current == report["original_surround"]:
            return
        command = ("settings delete global encoded_surround_output"
                   if report["original_surround"] == "null"
                   else "settings put global encoded_surround_output "
                   + report["original_surround"])
        shell(command)
        restored = shell("settings get global encoded_surround_output")
        if restored != report["original_surround"]:
            raise RuntimeError("Original audio setting was not restored")

    step("surround_restore", restore_surround)

    def remove_jar():
        state = shell("if [ -e " + shlex.quote(report["jar_path"])
                      + " ]; then echo PRESENT; else echo ABSENT; fi")
        if state == "ABSENT":
            return
        shell("rm -f " + shlex.quote(report["jar_path"]))
        state = shell("if [ -e " + shlex.quote(report["jar_path"])
                      + " ]; then echo PRESENT; else echo ABSENT; fi")
        if state != "ABSENT":
            raise RuntimeError("Owned measurement JAR remains present")

    step("jar_remove", remove_jar, refresh=True)


def finalize_report(report, save):
    pending = [name for name, step in report["cleanup"].items() if step["status"] != "done"]
    report["result"] = "CLEANUP_PENDING" if pending else report["capture_result"]
    report["cleanup_pending"] = pending
    report["finished_epoch"] = int(time.time())
    save()


def resume_cleanup(args, parser):
    measurement_flags = (args.jar, args.receiver_ip, args.app, args.output,
                         args.seconds, args.port, args.stream)
    if any(value is not None for value in measurement_flags):
        parser.error("--cleanup-report cannot be combined with capture options")
    report_path = pathlib.Path(args.cleanup_report)
    try:
        report = read_cleanup_report(report_path, args.device)
    except ValueError as error:
        parser.error(str(error))
    if report["capture_result"] == "RUNNING":
        report["capture_result"] = "CAPTURE_INTERRUPTED"
        if report["primary_error"] is None:
            report["primary_error"] = "Process ended before the capture result was recorded"
    save = lambda: atomic_write(report_path, report)
    if all(step["status"] == "done" for step in report["cleanup"].values()):
        finalize_report(report, save)
        print(json.dumps(report, indent=2))
        return
    save()
    run_cleanup(report, save, ["adb", "-s", args.device])
    finalize_report(report, save)
    print(json.dumps(report, indent=2))
    if report["result"] == "CLEANUP_PENDING":
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--cleanup-report")
    parser.add_argument("--jar", type=pathlib.Path)
    parser.add_argument("--receiver-ip")
    parser.add_argument("--port", type=int)
    parser.add_argument("--stream")
    parser.add_argument("--app")
    parser.add_argument("--seconds", type=int)
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    if args.cleanup_report:
        resume_cleanup(args, parser)
        return
    missing = [name for name in ("jar", "receiver_ip", "app", "output")
               if getattr(args, name) is None]
    if missing:
        parser.error("capture requires --jar, --receiver-ip, --app, and --output")
    args.port = 6980 if args.port is None else args.port
    args.stream = "vban-audio" if args.stream is None else args.stream
    args.seconds = 60 if args.seconds is None else args.seconds
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
        return subprocess.check_output(adb + ["shell", command], text=True,
                                       timeout=15).strip()

    def no_engine():
        processes = shell("ps -A -o ARGS")
        owners = [line.strip() for line in processes.splitlines()
                  if "app.vbansender.AudioEngine" in line
                  or "app.vbansender.CaptureSender" in line]
        if owners:
            raise RuntimeError("A VBAN capture owner is already running; select OFF before measuring")

    no_engine()
    activities = shell("dumpsys activity activities")
    resumed = [line.strip() for line in activities.splitlines() if "mResumedActivity:" in line]
    if not any(args.app + "/" in line for line in resumed):
        raise RuntimeError("Expected foreground app is absent: " + args.app)
    original_surround = shell("settings get global encoded_surround_output")
    if not re.fullmatch(r"null|[0-3]", original_surround):
        raise RuntimeError("Unexpected original surround setting: " + original_surround)
    original_stop = shell("if [ -e " + shlex.quote(REMOTE + "/stop")
                          + " ]; then echo PRESENT; else echo ABSENT; fi")
    if original_stop not in ("PRESENT", "ABSENT"):
        raise RuntimeError("Unexpected original stop marker state: " + original_stop)
    started_epoch = int(shell("date +%s"))
    run = uuid.uuid4().hex
    digest = hashlib.sha256(args.jar.read_bytes()).hexdigest()
    args.output.mkdir(parents=True, mode=0o700)
    report_path = args.output / "report.json"
    report = initial_report(args.device, args.app, run, digest, args.seconds,
                            original_surround, original_stop, started_epoch)
    save = lambda: atomic_write(report_path, report)
    save()  # Durable recovery data precedes the first remote mutation (adb push).
    process = None
    stdout = stderr = ""
    try:
        subprocess.run(adb + ["push", str(args.jar), report["jar_path"]], check=True,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
        installed_hash = shell("sha256sum " + shlex.quote(report["jar_path"])).split()[0]
        if installed_hash != digest:
            raise RuntimeError("Device measurement JAR hash differs from the local artifact")
        shell("mkdir -p " + REMOTE + " && chmod 700 " + REMOTE)
        shell("CLASSPATH=" + shlex.quote(report["jar_path"])
              + " app_process / app.vbansender.EnginePreparation")
        shell("settings put global encoded_surround_output 1")
        shell("touch " + shlex.quote(report["marker_path"]))
        command = ["app_process", "/", "app.vbansender.AudioEngine", str(destination),
                   str(args.port), args.stream, "256", run,
                   "--measure-seconds", str(args.seconds)]
        process = subprocess.Popen(adb + ["shell", "CLASSPATH=" + shlex.quote(report["jar_path"])
                                          + " " + shlex.join(command)],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        stdout, stderr = process.communicate(timeout=args.seconds + 20)
        (args.output / "engine.log").write_text(stdout + stderr, encoding="utf-8")
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
        report.update({"sessions": summaries,
                       "nonzero_pcm_observed": any(item.get("peak", 0) > 0 for item in metrics),
                       "final_state": final_state})
        report["capture_result"] = "PASS_CAPTURE_TIMING"
        save()
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
    except KeyboardInterrupt as error:
        report["capture_result"] = "CAPTURE_INTERRUPTED"
        report["primary_error"] = format_error(error)
        if process is not None:
            try:
                stdout, stderr = process.communicate(timeout=0.2)
            except subprocess.TimeoutExpired:
                pass
            if stdout or stderr:
                (args.output / "engine.log").write_text(stdout + stderr, encoding="utf-8")
    except Exception as error:
        report["capture_result"] = "CAPTURE_FAILED"
        report["primary_error"] = format_error(error)
        if isinstance(error, subprocess.TimeoutExpired):
            partial = "".join(
                part.decode(errors="replace") if isinstance(part, bytes) else (part or "")
                for part in (error.stdout, error.stderr))
            if partial:
                (args.output / "engine.log").write_text(partial, encoding="utf-8")
    finally:
        if process is not None:
            try:
                extra_stdout, extra_stderr = process.communicate(timeout=0)
                stdout = extra_stdout or stdout
                stderr = extra_stderr or stderr
            except subprocess.TimeoutExpired:
                pass
            if stdout or stderr:
                (args.output / "engine.log").write_text(stdout + stderr, encoding="utf-8")
        if report["primary_error"]:
            save()
        report["finished_epoch"] = int(time.time())
        run_cleanup(report, save, adb, process, args.output / "engine.log")
        finalize_report(report, save)
    print(json.dumps(report, indent=2))
    if report["result"] != "PASS_CAPTURE_TIMING":
        sys.exit(1)


if __name__ == "__main__":
    main()
