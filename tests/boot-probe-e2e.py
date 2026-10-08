#!/usr/bin/env python3
"""Exercise boot-probe.py with a synthetic local adb executable."""
import datetime
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUTPUT = pathlib.Path(os.environ.get("VBAN_SENDER_BOOT_E2E_OUTPUT", ROOT / "build" / "e2e" / "boot")).resolve()
PRODUCTION_TOOL = ROOT / "tools" / "boot-probe.py"

BOOT_ID_COMMAND = "cat /proc/sys/kernel/random/boot_id"
HEARTBEAT_COMMAND = "cat /data/local/tmp/vban-sender/heartbeat.txt"
BOOT_ID_BEFORE = "11111111-1111-4111-8111-111111111111"
BOOT_ID_AFTER = "22222222-2222-4222-8222-222222222222"
OLD_RUN = "a" * 32
NEW_RUN = "b" * 32

FAKE_ADB = r'''#!/usr/bin/env python3
import json
import os
import pathlib
import sys

state_path = pathlib.Path(os.environ["VBAN_SENDER_BOOT_FIXTURE_STATE"])
calls_path = pathlib.Path(os.environ["VBAN_SENDER_BOOT_FIXTURE_CALLS"])
state = json.loads(state_path.read_text())
args = sys.argv[1:]
with calls_path.open("a") as calls:
    calls.write(json.dumps(args) + "\n")

def fail(message, status=2):
    print(message, file=sys.stderr)
    raise SystemExit(status)

if args[:2] != ["-s", "localhost:5555"]:
    fail("unexpected adb endpoint: " + repr(args))
if args[2:3] == ["reboot"]:
    raise SystemExit(0)
if args[2:3] != ["shell"] or len(args) != 4:
    fail("unexpected adb command: " + repr(args))
command = args[3]

if command == "cat /proc/sys/kernel/random/boot_id":
    state["boot_reads"] += 1
    if state["fail_initial_boot_read"] and state["boot_reads"] == 1:
        print("synthetic boot identity read failure", file=sys.stderr)
        raise SystemExit(7)
    if state["baseline_replay"]:
        value = state["boot_after"]
    else:
        value = state["boot_before"] if state["boot_reads"] == 1 else state["boot_after"]
elif command == "cat /data/local/tmp/vban-sender/heartbeat.txt":
    state["heartbeat_reads"] += 1
    if state["baseline_replay"]:
        run = state["heartbeat_after_run"]
    else:
        run = state["heartbeat_before_run"] if state["heartbeat_reads"] == 1 else state["heartbeat_after_run"]
    value = "run=" + run + " uid=2000 pid=4242 tick=17\n"
elif command == "getprop sys.boot_completed":
    value = "1"
elif command == "logcat -d -s VBAN-PROOF:I":
    value = "I/VBAN-PROOF: Boot PASS: fixture run=" + state["logged_run"] + " uid=2000 pid=4242 tick=17\n"
elif command == "cat /data/local/tmp/vban-sender/heartbeat.log":
    value = "synthetic heartbeat log\n"
else:
    fail("unexpected adb shell command: " + command)

state_path.write_text(json.dumps(state))
print(value, end="" if value.endswith("\n") else "\n")
'''


def new_run_directory():
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    directory = OUTPUT / ("run-" + stamp + "-" + str(os.getpid()))
    directory.mkdir(parents=True, exist_ok=False)
    return directory


def scenario_state(boot_before=BOOT_ID_BEFORE, boot_after=BOOT_ID_AFTER,
                   heartbeat_before=OLD_RUN, heartbeat_after=OLD_RUN,
                   logged_run=OLD_RUN, fail_initial_boot_read=False,
                   baseline_replay=False):
    return {
        "boot_before": boot_before,
        "boot_after": boot_after,
        "boot_reads": 0,
        "heartbeat_before_run": heartbeat_before,
        "heartbeat_after_run": heartbeat_after,
        "heartbeat_reads": 0,
        "logged_run": logged_run,
        "fail_initial_boot_read": fail_initial_boot_read,
        "baseline_replay": baseline_replay,
    }


def make_fixture(run_directory, name, source=None, state=None):
    fixture_root = run_directory / (name + "-fixture")
    tools_directory = fixture_root / "tools"
    bin_directory = fixture_root / "bin"
    tools_directory.mkdir(parents=True)
    bin_directory.mkdir()

    if source is None:
        source = PRODUCTION_TOOL.read_text()
    (tools_directory / "boot-probe.py").write_text(source)

    state_path = fixture_root / "state.json"
    calls_path = fixture_root / "adb-calls.jsonl"
    state_path.write_text(json.dumps(state or scenario_state()))
    fake_adb = bin_directory / "adb"
    fake_adb.write_text(FAKE_ADB)
    fake_adb.chmod(0o700)
    return fixture_root, state_path, calls_path


def run_probe(run_directory, name, *, source=None, state=None, baseline_report=None,
              device="localhost:5555", cold_power_cycle=True):
    fixture_root, state_path, calls_path = make_fixture(run_directory, name, source, state)
    env = dict(os.environ)
    env["PATH"] = str(fixture_root / "bin") + os.pathsep + env.get("PATH", "")
    env["VBAN_SENDER_BOOT_FIXTURE_STATE"] = str(state_path)
    env["VBAN_SENDER_BOOT_FIXTURE_CALLS"] = str(calls_path)
    command = [sys.executable, str(fixture_root / "tools" / "boot-probe.py"),
               "--device", device, "--seconds", "10"]
    if cold_power_cycle:
        command.append("--cold-power-cycle")
    if baseline_report is not None:
        command.extend(["--baseline-report", str(baseline_report)])
    process = subprocess.run(command, cwd=fixture_root, env=env, capture_output=True,
                             text=True, timeout=20)
    output = process.stdout + process.stderr
    report_match = re.search(r"^(?:PASS|FAIL): (.+)$", process.stdout, re.MULTILINE)
    assert report_match, "boot-probe did not print a report path:\n" + output
    report_path = pathlib.Path(report_match.group(1)).resolve()
    expected_report_directory = (fixture_root / "build" / "device").resolve()
    assert report_path.parent == expected_report_directory, (
        "boot-probe wrote outside its isolated synthetic fixture: " + str(report_path))
    assert report_path.is_file(), "boot-probe report was not created: " + str(report_path)
    report = json.loads(report_path.read_text())

    synthetic_reports = run_directory / "synthetic-reports"
    synthetic_reports.mkdir(exist_ok=True)
    captured_report = synthetic_reports / (name + "-synthetic-boot-proof.json")
    assert not captured_report.exists(), "refusing to overwrite synthetic report: " + str(captured_report)
    shutil.move(str(report_path), str(captured_report))
    calls = ([json.loads(line) for line in calls_path.read_text().splitlines()]
             if calls_path.exists() else [])
    return {
        "exit": process.returncode,
        "stdout": process.stdout,
        "stderr": process.stderr,
        "report": report,
        "report_path": str(captured_report),
        "calls": calls,
    }


def write_cached_baseline(run_directory, name, *, device="localhost:5555",
                          boot_id=BOOT_ID_BEFORE, heartbeat_run=OLD_RUN):
    path = run_directory / (name + "-cached-baseline.json")
    report = {
        "device": device,
        "kind": "physical power cycle operated by user",
        "events": [],
        "result": "FAIL",
        "scope": "Synthetic pre-cycle baseline captured by a prior cold-power-cycle probe",
        "boot_id_before": boot_id,
        "heartbeat_before": {
            "exit": 0,
            "out": "run=" + heartbeat_run + " uid=2000 pid=4242 tick=17\n",
            "err": "",
        },
        "error": "No successful APK boot heartbeat within the finite window",
    }
    path.write_text(json.dumps(report, indent=2) + "\n")
    return path


def run_without_cold_flag(run_directory, name, baseline_report):
    fixture_root, state_path, calls_path = make_fixture(run_directory, name)
    env = dict(os.environ)
    env["PATH"] = str(fixture_root / "bin") + os.pathsep + env.get("PATH", "")
    env["VBAN_SENDER_BOOT_FIXTURE_STATE"] = str(state_path)
    env["VBAN_SENDER_BOOT_FIXTURE_CALLS"] = str(calls_path)
    command = [sys.executable, str(fixture_root / "tools" / "boot-probe.py"),
               "--device", "localhost:5555", "--seconds", "10",
               "--baseline-report", str(baseline_report)]
    process = subprocess.run(command, cwd=fixture_root, env=env, capture_output=True,
                             text=True, timeout=10)
    assert process.returncode == 2, (
        "--baseline-report without --cold-power-cycle was not parser-rejected: "
        + process.stdout + process.stderr)
    assert "--baseline-report requires --cold-power-cycle" in process.stderr, process.stderr
    assert not calls_path.exists() or not calls_path.read_text().strip(), (
        "ADB was called before rejecting --baseline-report without cold mode")
    return {"exit": process.returncode, "stderr": process.stderr}


def cold_mode_did_not_reboot(result):
    assert not any(call[2:] == ["reboot"] for call in result["calls"]), (
        "cold-power-cycle mode issued adb reboot: " + repr(result["calls"]))


def reconstruct_prechange_source():
    source = PRODUCTION_TOOL.read_text()
    current = 'new_run = match and ("run=" + match[0] + " uid=2000 ") not in report["heartbeat_before"]["out"]'
    previous = 'new_run = match and (not args.reboot or ("run=" + match[0] + " uid=2000 ") not in report["heartbeat_before"]["out"])'
    assert source.count(current) == 1, "cannot reconstruct the recorded pre-change condition"
    return source.replace(current, previous, 1)


def main():
    assert PRODUCTION_TOOL.is_file(), "missing production boot-probe.py"
    OUTPUT.mkdir(parents=True, exist_ok=True)
    run_directory = new_run_directory()
    results = {}

    # This exact temporary reconstruction captures the previously observed
    # `not args.reboot` condition. It is regression evidence, not historical source.
    old_behavior = run_probe(run_directory, "prechange-stale-heartbeat",
                             source=reconstruct_prechange_source(),
                             state=scenario_state())
    assert old_behavior["exit"] == 0 and old_behavior["report"]["result"] == "PASS", (
        "the pre-change condition did not reproduce stale-heartbeat acceptance: "
        + old_behavior["stdout"] + old_behavior["stderr"])
    assert old_behavior["report"].get("heartbeat_run") == OLD_RUN
    cold_mode_did_not_reboot(old_behavior)
    results["prechange_reconstruction"] = {
        "evidence": "REGRESSION_REPRODUCED",
        "condition": 'new_run = match and (not args.reboot or ("run=" + match[0] + " uid=2000 ") not in report["heartbeat_before"]["out"])',
        "report": old_behavior["report_path"],
    }

    stale = run_probe(run_directory, "stale-heartbeat-rejected", state=scenario_state())
    assert stale["exit"] == 1 and stale["report"]["result"] == "FAIL", (
        "valid Boot PASS with the old heartbeat run was accepted:\n" + stale["stdout"] + stale["stderr"])
    assert stale["report"]["boot_id_before"] != BOOT_ID_AFTER
    assert stale["report"]["events"][-1]["heartbeat"]["out"].find("run=" + OLD_RUN) >= 0
    cold_mode_did_not_reboot(stale)
    results["stale_heartbeat"] = {"exit": stale["exit"], "error": stale["report"].get("error"),
                                   "report": stale["report_path"]}

    unchanged_boot = run_probe(
        run_directory, "unchanged-boot-id-rejected",
        state=scenario_state(boot_after=BOOT_ID_BEFORE,
                             heartbeat_before=OLD_RUN, heartbeat_after=NEW_RUN,
                             logged_run=NEW_RUN))
    assert unchanged_boot["exit"] == 1 and unchanged_boot["report"]["result"] == "FAIL", (
        "a fresh heartbeat passed cold mode without a changed boot_id:\n"
        + unchanged_boot["stdout"] + unchanged_boot["stderr"])
    cold_mode_did_not_reboot(unchanged_boot)
    results["unchanged_boot_id"] = {"exit": unchanged_boot["exit"],
                                    "error": unchanged_boot["report"].get("error"),
                                    "report": unchanged_boot["report_path"]}

    fresh = run_probe(
        run_directory, "changed-boot-and-fresh-heartbeat-pass",
        state=scenario_state(heartbeat_after=NEW_RUN, logged_run=NEW_RUN))
    assert fresh["exit"] == 0 and fresh["report"]["result"] == "PASS", (
        "changed boot identity and fresh Boot PASS heartbeat did not pass:\n"
        + fresh["stdout"] + fresh["stderr"])
    assert fresh["report"]["boot_id_before"] == BOOT_ID_BEFORE
    assert fresh["report"]["boot_id_after"] == BOOT_ID_AFTER
    assert fresh["report"]["heartbeat_run"] == NEW_RUN
    cold_mode_did_not_reboot(fresh)
    results["changed_boot_and_fresh_heartbeat"] = {
        "exit": fresh["exit"], "result": fresh["report"]["result"],
        "report": fresh["report_path"],
    }

    read_failure = run_probe(
        run_directory, "initial-boot-id-read-failure",
        state=scenario_state(fail_initial_boot_read=True))
    assert read_failure["exit"] == 1 and read_failure["report"]["result"] == "FAIL", (
        "initial boot identity read failure was not a visible FAIL:\n"
        + read_failure["stdout"] + read_failure["stderr"])
    assert "Cannot read boot identity before the probe" in read_failure["report"].get("error", "")
    assert read_failure["stdout"].startswith("FAIL: "), read_failure["stdout"]
    assert len(read_failure["calls"]) == 1, "probe continued after the initial identity read failed"
    cold_mode_did_not_reboot(read_failure)
    results["initial_read_failure"] = {
        "exit": read_failure["exit"], "error": read_failure["report"]["error"],
        "report": read_failure["report_path"],
    }

    cached_baseline = write_cached_baseline(run_directory, "after-cycle-baseline")
    already_restarted = run_probe(
        run_directory, "cached-baseline-after-startup-passes",
        baseline_report=cached_baseline,
        state=scenario_state(boot_before=BOOT_ID_AFTER, boot_after=BOOT_ID_AFTER,
                             heartbeat_after=NEW_RUN, logged_run=NEW_RUN,
                             baseline_replay=True))
    assert already_restarted["exit"] == 0 and already_restarted["report"]["result"] == "PASS", (
        "cached baseline did not accept the already completed cold power cycle:\n"
        + already_restarted["stdout"] + already_restarted["stderr"])
    assert already_restarted["report"]["boot_id_before"] == BOOT_ID_BEFORE
    assert already_restarted["report"]["boot_id_after"] == BOOT_ID_AFTER
    assert already_restarted["report"]["heartbeat_run"] == NEW_RUN
    assert already_restarted["report"]["baseline_report"] == str(cached_baseline.resolve())
    assert already_restarted["calls"] and already_restarted["calls"][0][3] == "getprop sys.boot_completed", (
        "cached baseline mode unexpectedly tried to read a new pre-cycle baseline")
    cold_mode_did_not_reboot(already_restarted)
    results["cached_baseline_after_startup"] = {
        "exit": already_restarted["exit"], "result": already_restarted["report"]["result"],
        "baseline": str(cached_baseline), "report": already_restarted["report_path"],
    }

    unchanged_cached = run_probe(
        run_directory, "cached-baseline-without-new-startup-fails",
        baseline_report=cached_baseline,
        state=scenario_state(boot_before=BOOT_ID_BEFORE, boot_after=BOOT_ID_BEFORE,
                             heartbeat_after=OLD_RUN, logged_run=OLD_RUN,
                             baseline_replay=True))
    assert unchanged_cached["exit"] == 1 and unchanged_cached["report"]["result"] == "FAIL", (
        "cached baseline accepted the unchanged boot identity and stale heartbeat:\n"
        + unchanged_cached["stdout"] + unchanged_cached["stderr"])
    assert unchanged_cached["report"]["events"][-1]["boot_id"]["out"].strip() == BOOT_ID_BEFORE
    assert "run=" + OLD_RUN in unchanged_cached["report"]["events"][-1]["heartbeat"]["out"]
    cold_mode_did_not_reboot(unchanged_cached)
    results["cached_baseline_without_new_startup"] = {
        "exit": unchanged_cached["exit"], "error": unchanged_cached["report"].get("error"),
        "report": unchanged_cached["report_path"],
    }

    other_device_baseline = write_cached_baseline(
        run_directory, "other-device-baseline", device="192.0.2.8:5555")
    other_device = run_probe(
        run_directory, "baseline-other-device-rejected",
        baseline_report=other_device_baseline)
    assert other_device["exit"] == 1 and other_device["report"]["result"] == "FAIL"
    assert "different device or probe kind" in other_device["report"].get("error", "")
    assert other_device["calls"] == [], "ADB was called for a baseline belonging to another device"
    results["baseline_other_device"] = {
        "exit": other_device["exit"], "error": other_device["report"]["error"],
        "report": other_device["report_path"],
    }

    invalid_boot_baseline = write_cached_baseline(
        run_directory, "invalid-boot-identity-baseline", boot_id="not-a-boot-id")
    invalid_boot = run_probe(
        run_directory, "invalid-boot-identity-rejected",
        baseline_report=invalid_boot_baseline)
    assert invalid_boot["exit"] == 1 and invalid_boot["report"]["result"] == "FAIL"
    assert "Invalid saved boot identity" in invalid_boot["report"].get("error", "")
    assert invalid_boot["calls"] == [], "ADB was called for a malformed saved boot identity"
    results["invalid_saved_boot_identity"] = {
        "exit": invalid_boot["exit"], "error": invalid_boot["report"]["error"],
        "report": invalid_boot["report_path"],
    }

    invalid_heartbeat_baseline = write_cached_baseline(
        run_directory, "invalid-heartbeat-baseline", heartbeat_run="not-a-run-id")
    invalid_heartbeat = run_probe(
        run_directory, "invalid-heartbeat-identity-rejected",
        baseline_report=invalid_heartbeat_baseline)
    assert invalid_heartbeat["exit"] == 1 and invalid_heartbeat["report"]["result"] == "FAIL"
    assert "lacks a valid heartbeat identity" in invalid_heartbeat["report"].get("error", "")
    assert invalid_heartbeat["calls"] == [], "ADB was called for a malformed saved heartbeat identity"
    results["invalid_saved_heartbeat_identity"] = {
        "exit": invalid_heartbeat["exit"], "error": invalid_heartbeat["report"]["error"],
        "report": invalid_heartbeat["report_path"],
    }

    parser_reject = run_without_cold_flag(
        run_directory, "baseline-without-cold-flag", cached_baseline)
    results["baseline_requires_cold_flag"] = parser_reject

    summary = {
        "result": "PASS",
        "scope": "Production boot-probe CLI with a synthetic PATH adb; no live ADB, SSH, device, or build runner",
        "prechange_reconstruction_note": "Temporary exact reconstruction of the previously observed new_run condition; not an immutable historical source copy",
        "scenarios": results,
    }
    report_path = run_directory / "report.json"
    report_path.write_text(json.dumps(summary, indent=2) + "\n")
    print("PASS: boot probe cached baseline, stale heartbeat, cold boot identity, fresh heartbeat, reboot exclusion, and input failures")
    print("Synthetic evidence: " + str(report_path))


if __name__ == "__main__":
    main()
