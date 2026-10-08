#!/usr/bin/env python3
"""Production foreground observer/controller, subprocess failures, and real synthetic UDP."""
import json
import os
import pathlib
import queue
import socket
import struct
import subprocess
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build" / "e2e" / "routing"
JAVA = ["java", "-cp", str(ROOT / "build/classes") + ":" + str(ROOT / "build/test-classes"),
        "app.vbansender.AppAwareHarness"]
YOUTUBE = "org.smarttube.stable"
PRIME = "com.amazon.amazonvideo.livingroom"
NETFLIX = "com.netflix.ninja"


def dump(package):
    return f"mResumedActivity: ActivityRecord{{fixture u0 {package}/.MainActivity t1}}\n"


class Trial:
    def __init__(self, name, initial, seconds=15, continuous=False, marker=True):
        self.directory = OUTPUT / name
        self.directory.mkdir(parents=True, exist_ok=True)
        self.foreground = self.directory / "foreground.txt"
        self.stop = self.directory / "stop"
        self.marker = self.directory / "enabled-run"
        if self.stop.exists():
            self.stop.unlink()
        if self.marker.exists():
            self.marker.unlink()
        if continuous and marker:
            self.marker.touch()
        self.foreground.write_text(initial)
        self.receiver = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.receiver.bind(("127.0.0.1", 0))
        self.receiver.settimeout(.02)
        self.events = []
        self.packets = 0
        self.lines = queue.Queue()
        self.stderr_path = self.directory / "stderr.log"
        self.stderr = self.stderr_path.open("w")
        env = dict(os.environ, VBAN_SENDER_TEST_FOREGROUND=str(self.foreground),
                   PATH=str(OUTPUT / "bin") + os.pathsep + os.environ["PATH"])
        command = [str(self.receiver.getsockname()[1]), "continuous", str(self.marker),
                   str(self.stop)] if continuous else [str(self.receiver.getsockname()[1]),
                                                        str(seconds), str(self.stop)]
        self.process = subprocess.Popen(JAVA + command, env=env, stdout=subprocess.PIPE,
                                        stderr=self.stderr, text=True)
        self.reader = threading.Thread(target=self.read_lines)
        self.reader.start()

    def read_lines(self):
        for line in self.process.stdout:
            self.lines.put((time.monotonic(), line.strip()))

    def pump(self, seconds):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            try:
                data, _ = self.receiver.recvfrom(2048)
                assert data[:4] == b"VBAN" and len(data) == 32
                assert struct.unpack_from("<I", data, 24)[0] == self.packets, "lost/reordered loopback packet"
                self.packets += 1
            except socket.timeout:
                pass
            while not self.lines.empty():
                at, line = self.lines.get_nowait()
                self.events.append({"time": at, "event": line})

    def change(self, contents):
        temporary = self.foreground.with_suffix(".tmp")
        temporary.write_text(contents)
        temporary.replace(self.foreground)
        return time.monotonic()

    def wait(self, prefix, after=0, timeout=3):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            self.pump(.03)
            for event in self.events:
                if event["time"] >= after and event["event"].startswith(prefix):
                    return event["time"]
        raise AssertionError(f"missing event {prefix}: {self.events}; {self.stderr_path.read_text()}")

    def quiet(self, seconds=.3):
        before = self.packets
        self.pump(seconds)
        assert self.packets == before, "audio sent while capture should be disabled"

    def finish(self, expected=0):
        self.process.wait(timeout=4)
        self.reader.join(timeout=1)
        self.pump(.05)
        assert self.process.returncode == expected, self.stderr_path.read_text()
        self.quiet(.1)
        report = {"exit": self.process.returncode, "packets": self.packets, "events": self.events}
        (self.directory / "events.json").write_text(json.dumps(report, indent=2) + "\n")
        return report

    def close(self):
        if self.process.poll() is None:
            self.process.kill()
            self.process.wait()
        self.reader.join(timeout=1)
        self.process.stdout.close()
        self.stderr.close()
        self.receiver.close()


def transitions():
    trial = Trial("transitions", dump(NETFLIX))
    try:
        trial.wait("FOREGROUND " + NETFLIX)
        trial.quiet()
        changed = trial.change(dump(YOUTUBE))
        trial.wait("SESSION_STARTED", changed)
        trial.pump(.15)
        assert trial.packets > 0
        changed = trial.change(dump(NETFLIX))
        released = trial.wait("SESSION_RELEASED", changed)
        assert released - changed < 1, "Netflix stop reaction too slow on synthetic fixture"
        trial.quiet()
        changed = trial.change(dump(PRIME))
        trial.wait("SESSION_STARTED", changed)
        changed = trial.change(dump(PRIME) + dump(NETFLIX))
        trial.wait("SESSION_RELEASED", changed)
        trial.quiet()
        # A Netflix process remaining in the background does not disable foreground YouTube.
        changed = trial.change("background process com.netflix.ninja\n" + dump(YOUTUBE))
        trial.wait("SESSION_STARTED", changed)
        changed = trial.change(dump("com.vendor.launcher"))
        trial.wait("SESSION_RELEASED", changed)
        trial.quiet()
        # Stop during a pending restart must cancel it, even when another allowed app appears.
        trial.change(dump(PRIME))
        trial.pump(.35)
        stopped = time.monotonic()
        trial.stop.touch()
        trial.change(dump(YOUTUBE))
        report = trial.finish()
        assert not any(e["event"] == "SESSION_STARTED" and e["time"] > stopped
                       for e in trial.events), "capture restarted after manual Stop"
        return report
    finally:
        trial.close()


def failure():
    trial = Trial("detector-failure", dump(YOUTUBE))
    try:
        trial.wait("SESSION_STARTED")
        trial.pump(.15)
        changed = trial.change("ERROR")
        trial.wait("SESSION_RELEASED", changed)
        report = trial.finish(expected=1)
        assert "Foreground detection failed" in trial.stderr_path.read_text()
        return report
    finally:
        trial.close()


def sleep_gate():
    trial = Trial("sleep-gate", dump(YOUTUBE) + "mSleeping=true\n")
    try:
        trial.wait("FOREGROUND unknown")
        trial.quiet(.35)
        awake = trial.change(dump(YOUTUBE))
        trial.wait("SESSION_STARTED", awake)
        sleeping = trial.change(dump(YOUTUBE) + "mSleeping=true\n")
        trial.wait("SESSION_RELEASED", sleeping)
        trial.quiet()
        trial.stop.touch()
        return trial.finish()
    finally:
        trial.close()


def canceled_before_start():
    trial = Trial("canceled-before-start", dump(YOUTUBE), continuous=True, marker=False)
    try:
        trial.wait("CONTROLLER_STOPPED")
        report = trial.finish()
        assert trial.packets == 0, "engine captured after its run marker was absent"
        assert not any(e["event"].startswith("SESSION_STARTED") for e in trial.events)
        return report
    finally:
        trial.close()


def marker_stops_sending():
    trial = Trial("marker-stops-sending", dump(YOUTUBE), continuous=True)
    try:
        trial.wait("SESSION_STARTED")
        trial.pump(.2)
        assert trial.packets > 0
        trial.marker.unlink()
        trial.wait("SESSION_RELEASED")
        report = trial.finish()
        assert report["packets"] > 0
        return report
    finally:
        trial.close()


def marker_stops_idle():
    trial = Trial("marker-stops-idle", dump(NETFLIX), continuous=True)
    try:
        trial.wait("ENGINE_STATE IDLE")
        trial.quiet(.3)
        trial.marker.unlink()
        report = trial.finish()
        assert report["packets"] == 0
        assert not any(e["event"].startswith("SESSION_STARTED") for e in trial.events)
        return report
    finally:
        trial.close()


def stale():
    trial = Trial("stale-and-stop", dump(PRIME))
    try:
        trial.wait("SESSION_STARTED")
        changed = trial.change("STALL")
        trial.wait("SESSION_RELEASED", changed, timeout=2.5)
        trial.quiet()
        trial.stop.touch()
        return trial.finish()
    finally:
        trial.close()


def deadline():
    trial = Trial("deadline", dump(YOUTUBE), seconds=2)
    try:
        trial.wait("SESSION_STARTED")
        return trial.finish()
    finally:
        trial.close()


def main():
    directory = OUTPUT / "bin"
    directory.mkdir(parents=True, exist_ok=True)
    fake = directory / "dumpsys"
    fake.write_text("""#!/usr/bin/env python3
import os,pathlib,sys,time
text=pathlib.Path(os.environ['VBAN_SENDER_TEST_FOREGROUND']).read_text()
if text=='ERROR':
    print('fixture detector failure',file=sys.stderr)
    sys.exit(7)
if text=='STALL':
    time.sleep(3)
    text=pathlib.Path(os.environ['VBAN_SENDER_TEST_FOREGROUND']).read_text()
print(text)
""")
    fake.chmod(0o700)
    status_directory = OUTPUT / "status-check"
    status_directory.mkdir(parents=True, exist_ok=True)
    # 2000 fsynced writes test atomicity, not the host disk's throughput.
    status = subprocess.run(JAVA + ["--status-check", str(status_directory)],
                            capture_output=True, text=True, timeout=30)
    assert status.returncode == 0 and "STATUS_CHECK_PASS" in status.stdout, \
        (status.stdout, status.stderr)
    results = {"transitions": transitions(), "sleep_gate": sleep_gate(),
               "canceled_before_start": canceled_before_start(),
               "marker_stops_sending": marker_stops_sending(),
               "marker_stops_idle": marker_stops_idle(), "detector_failure": failure(),
               "stale_and_stop": stale(), "finite_deadline": deadline()}
    report = {"result": "PASS", "scope": "Real observer/controller, synthetic dumpsys and UDP backend; no Android audio proof",
              "scenarios": results}
    (OUTPUT / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("PASS: application transitions, sleep gate, run-marker cancellation, status atomicity, finite deadline")


if __name__ == "__main__":
    main()
