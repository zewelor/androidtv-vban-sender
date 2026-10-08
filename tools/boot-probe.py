#!/usr/bin/env python3
"""Observe the finite APK boot proof; optionally reboot the explicitly selected box."""
import argparse
import datetime
import json
import pathlib
import re
import subprocess
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True, help="Exact authorized ADB serial")
    parser.add_argument("--seconds", type=int, default=180)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--reboot", action="store_true", help="Perform one Android reboot, not a power-cut cold boot")
    mode.add_argument("--cold-power-cycle", action="store_true",
                      help="Observe a physical power cycle performed by the user after READY; never sends reboot")
    parser.add_argument("--baseline-report", type=pathlib.Path,
                        help="Resume cold observation from a saved report's pre-power-cycle baseline")
    args = parser.parse_args()
    if not 10 <= args.seconds <= 300:
        parser.error("seconds must be 10..300")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+:[0-9]{1,5}", args.device):
        parser.error("this probe requires the exact network ADB serial host:port")
    if args.baseline_report and not args.cold_power_cycle:
        parser.error("--baseline-report requires --cold-power-cycle")
    adb = ["adb", "-s", args.device]
    kind = "physical power cycle operated by user" if args.cold_power_cycle else (
        "Android reboot" if args.reboot else "observation of a new heartbeat")
    report = {"device": args.device, "kind": kind, "events": [], "result": "FAIL",
              "scope": "Finite heartbeat, no audio; physical power action requires user confirmation"}
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    path = ROOT / "build" / "device" / ("boot-proof-" + stamp + ".json")
    path.parent.mkdir(parents=True, exist_ok=True)

    def command(arguments, timeout=5):
        try:
            result = subprocess.run(arguments, capture_output=True, text=True, timeout=timeout)
            return {"exit": result.returncode, "out": result.stdout, "err": result.stderr}
        except subprocess.TimeoutExpired:
            return {"exit": None, "out": "", "err": "command timeout"}

    def shell(text):
        return command(adb + ["shell", text])

    try:
        if args.baseline_report:
            baseline = json.loads(args.baseline_report.read_text())
            if baseline["device"] != args.device or baseline["kind"] != "physical power cycle operated by user":
                raise RuntimeError("Saved baseline belongs to a different device or probe kind")
            boot_id = baseline["boot_id_before"]
            heartbeat = baseline["heartbeat_before"]
            if not re.fullmatch(r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}", boot_id):
                raise RuntimeError("Invalid saved boot identity")
            if heartbeat["exit"] != 0 or not re.search(r"run=[a-f0-9]{32} uid=2000 ", heartbeat["out"]):
                raise RuntimeError("Saved baseline lacks a valid heartbeat identity")
            report["boot_id_before"] = boot_id
            report["heartbeat_before"] = heartbeat
            report["baseline_report"] = str(args.baseline_report.resolve())
        else:
            before = shell("cat /proc/sys/kernel/random/boot_id")
            if before["exit"] != 0:
                raise RuntimeError("Cannot read boot identity before the probe: " + before["err"])
            report["boot_id_before"] = before["out"].strip()
            report["heartbeat_before"] = shell("cat /data/local/tmp/vban-sender/heartbeat.txt")
        if args.reboot:
            result = command(adb + ["reboot"], timeout=10)
            report["reboot"] = result
            if result["exit"] != 0:
                raise RuntimeError("Reboot request failed: " + result["err"])
            print("Reboot requested; waiting for the APK's own boot proof", flush=True)
        elif args.cold_power_cycle:
            print("Observing from the saved pre-cycle baseline" if args.baseline_report else
                  "READY: baseline saved; waiting for the user to cycle power", flush=True)

        deadline = time.monotonic() + args.seconds
        while time.monotonic() < deadline:
            time.sleep(3)
            ready = shell("getprop sys.boot_completed")
            if ready["exit"] != 0:
                # A disconnect is expected during reboot. Retry only the named endpoint.
                reconnect = command(["adb", "connect", args.device], timeout=3)
                report["events"].append({"ready": ready, "reconnect": reconnect})
                print("Waiting for the selected box", flush=True)
                continue
            if ready["out"].strip() != "1":
                report["events"].append({"ready": ready})
                continue
            boot = shell("cat /proc/sys/kernel/random/boot_id")
            heartbeat = shell("cat /data/local/tmp/vban-sender/heartbeat.txt")
            logs = shell("logcat -d -s VBAN-PROOF:I")
            event = {"boot_id": boot, "heartbeat": heartbeat, "app_log": logs}
            report["events"].append(event)
            new_boot = not (args.reboot or args.cold_power_cycle) or (
                boot["exit"] == 0 and boot["out"].strip() != report["boot_id_before"])
            matches = re.findall(r"Boot PASS:.*?run=([a-f0-9]{32}) uid=2000 pid=(\d+) tick=(\d+)",
                                 logs["out"], re.S)
            match = matches[-1] if matches else None
            new_run = match and ("run=" + match[0] + " uid=2000 ") not in report["heartbeat_before"]["out"]
            if new_boot and new_run and ("run=" + match[0] + " uid=2000 ") in heartbeat["out"]:
                report["result"] = "PASS"
                report["boot_id_after"] = boot["out"].strip()
                report["heartbeat_run"] = match[0]
                report["heartbeat_pid"] = int(match[1])
                report["heartbeat_log"] = shell("cat /data/local/tmp/vban-sender/heartbeat.log")
                break
            if "FAIL boot:" in logs["out"]:
                raise RuntimeError("APK reported boot failure; see the saved app log")
            print("Box booted; waiting for the APK heartbeat", flush=True)
        else:
            raise RuntimeError("No successful APK boot heartbeat within the finite window")
    except Exception as error:
        report["error"] = str(error)
    finally:
        path.write_text(json.dumps(report, indent=2) + "\n")
    print(report["result"] + ": " + str(path), flush=True)
    if report["result"] != "PASS":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
