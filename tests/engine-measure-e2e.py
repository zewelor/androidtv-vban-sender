#!/usr/bin/env python3
"""Run the measurement CLI against scripted ADB outcomes; no Android audio claim."""
import json
import contextlib
import importlib.util
import io
import os
import pathlib
import signal
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/review-cleanup/engine-measure"
FAKE_ADB = r'''#!/usr/bin/env python3
import hashlib,json,os,pathlib,shlex,sys,time
p=pathlib.Path(os.environ['MEASURE_FIXTURE']); journal=pathlib.Path(os.environ['MEASURE_JOURNAL'])
s=json.loads(p.read_text()); a=sys.argv[1:]
assert a[:2]==['-s','fixture-device'],a
def save(): p.write_text(json.dumps(s))
s.setdefault('adb_calls',[]).append(a[2:]); save()
def fail(name):
 n=s.setdefault('failures_seen',{}).get(name,0)+1; s['failures_seen'][name]=n; save()
 rule=s.get('failures',{}).get(name)
 if rule=='always' or (rule=='once' and n==1):
  print('injected '+name+' failure',file=sys.stderr); sys.exit(9)
def event(name):
 s.setdefault('events',[]).append({'name':name,'journal_exists':journal.is_file()}); save()
if a[2]=='push':
 assert journal.is_file(), 'cleanup journal must be durable before adb push'
 r=json.loads(journal.read_text())
 assert r['device']=='fixture-device' and len(r['run'])==32
 assert r['marker_path']=='/data/local/tmp/vban-sender/enabled-'+r['run']
 assert r['jar_path'].startswith('/data/local/tmp/vban-measure-'+r['run']+'-')
 assert r['original_surround'] in ('null','0','1','2','3') and r['original_stop'] in ('PRESENT','ABSENT')
 event('push'); s['jars'][a[4]]=hashlib.sha256(pathlib.Path(a[3]).read_bytes()).hexdigest(); save(); sys.exit()
assert a[2]=='shell',a
c=a[3]
if c=='ps -A -o ARGS':
 s['ps_checks']=s.get('ps_checks',0)+1; save()
 if s.get('mode')=='active-engine': print('app_process / app.vbansender.AudioEngine foreign-run')
 if s.get('mode')=='active-sender': print('app_process / app.vbansender.CaptureSender foreign-run')
 if s.get('linger') and s.get('ps_checks',0)>=3:
  print('app_process / app.vbansender.AudioEngine delayed-run')
  print('app_process / app.vbansender.CaptureSender foreign-run')
elif c=='dumpsys activity activities':
 app='com.other.app' if s.get('mode')=='wrong-app' else 'com.amazon.amazonvideo.livingroom'
 print('mResumedActivity: ActivityRecord{fixture u0 '+app+'/.MainActivity t1}')
elif c=='settings get global encoded_surround_output': print(s['surround'])
elif c.startswith('if [ -e '):
 path=shlex.split(c)[3]
 present=(s['stop'] if path.endswith('/stop') else
          path in s.get('markers',[]) or path in s.get('jars',{}))
 print('PRESENT' if present else 'ABSENT')
elif c=='date +%s': print(1793606400)
elif c.startswith('sha256sum '): print(s['jars'][shlex.split(c)[1]]+'  jar')
elif c.startswith('mkdir -p '): pass
elif c.startswith('settings put global encoded_surround_output '):
 value=c.split()[-1]
 if value!='1': fail('surround_restore')
 else: event('surround_enable')
 s['surround']=value; save()
elif c=='settings delete global encoded_surround_output':
 fail('surround_restore'); event('surround_restore'); s['surround']='null'; save()
elif c.startswith('touch '):
 path=shlex.split(c)[1]
 if path.endswith('/stop'):
  fail('stop_restore'); event('stop_restore'); s['stop']=True
 else: s['markers'].append(path)
 save()
elif c.startswith('CLASSPATH='):
 t=shlex.split(c); run=t[8]; s['launch_seen']=True
 s['stop']=False
 s['final']='run='+run+' uid=2000 pid=123 state='+('ERROR' if s.get('mode')=='bad-state' else 'STOPPED')+' message=none'
 save()
 if s.get('mode')=='engine-error': print('fixture engine failed',file=sys.stderr);sys.exit(7)
 if s.get('pause_engine'):
  print('partial capture log line',flush=True); time.sleep(60)
 print(json.dumps({'state':'CAPTURE_ENDED','frames':256,'sent':1,'dropped':0,'peak':1}))
elif c.startswith('cat /data/local/tmp/vban-sender/engine-state.txt'): print(s.get('final',''))
elif c.startswith('logcat '):
 print('1793600000.000 1 1 W AudioFlinger: RecordThread: buffer overflow')
 print('1793606400.500 1 1 W AudioFlinger: RecordThread: buffer overflow')
elif c.startswith('rm -f '):
 path=shlex.split(c)[2]
 name=('marker_remove' if '/enabled-' in path else
       'stop_restore' if path.endswith('/stop') else 'jar_remove')
 fail(name); event(name)
 if path.endswith('/stop'): s['stop']=False
 s['jars'].pop(path,None)
 if path in s['markers']: s['markers'].remove(path)
 save()
else: raise RuntimeError('Unexpected fixture command: '+c)
'''


def setup(mode, original="0", stop=False, failures=None, linger=False, pause_engine=False):
    directory = OUTPUT / mode
    directory.mkdir(parents=True, exist_ok=True)
    state = directory / "state.json"
    state.write_text(json.dumps({"mode": mode, "surround": original, "stop": stop,
                                 "markers": [], "jars": {}, "failures": failures or {},
                                 "linger": linger, "pause_engine": pause_engine}))
    jar = directory / "fixture.jar"
    jar.write_bytes(b"synthetic measurement artifact")
    result_path = directory / "result"
    if result_path.exists():
        import shutil
        shutil.rmtree(result_path)
    env = dict(os.environ, MEASURE_FIXTURE=str(state),
               MEASURE_JOURNAL=str(result_path / "report.json"),
               PATH=str(OUTPUT / "bin") + os.pathsep + os.environ["PATH"])
    return directory, state, jar, result_path, env


def invoke(state, jar, result_path, env, extra=()):
    return subprocess.run([sys.executable, str(ROOT / "tools/measure-engine.py"),
                           "--device", "fixture-device", "--jar", str(jar),
                           "--receiver-ip", "192.0.2.20", "--seconds", "1",
                           "--app", "com.amazon.amazonvideo.livingroom",
                           "--output", str(result_path), *extra], env=env,
                          capture_output=True, text=True, timeout=12)


def resume(state, result_path, env, device="fixture-device", report_path=None):
    return subprocess.run([sys.executable, str(ROOT / "tools/measure-engine.py"),
                           "--device", device, "--cleanup-report",
                           str(report_path or (result_path / "report.json"))],
                          env=env, capture_output=True, text=True, timeout=12)


def assert_clean(state, original, stop, result):
    after = json.loads(state.read_text())
    assert after["surround"] == original, (after, result.stderr)
    assert after["stop"] is stop, after
    assert not after["jars"] and not after["markers"], after
    return after


def happy_and_idempotent():
    _, state, jar, out, env = setup("ok", "null", stop=True)
    result = invoke(state, jar, out, env)
    assert result.returncode == 0, (result.stdout, result.stderr)
    report = json.loads((out / "report.json").read_text())
    assert report["result"] == "PASS_CAPTURE_TIMING" and report["nonzero_pcm_observed"]
    assert len(report["audio_errors"]) == 1
    state_after = assert_clean(state, "null", True, result)
    assert state_after["failures_seen"]["surround_restore"] == 1
    state_after["surround"] = "2"
    state_after["stop"] = False
    state.write_text(json.dumps(state_after))
    calls_before = len(state_after["adb_calls"])
    resumed = resume(state, out, env)
    assert resumed.returncode == 0, (resumed.stdout, resumed.stderr)
    after_resume = json.loads(state.read_text())
    assert len(after_resume["adb_calls"]) == calls_before, after_resume
    assert after_resume["surround"] == "2" and after_resume["stop"] is False, after_resume
    assert after_resume["failures_seen"]["surround_restore"] == 1, after_resume


def retries_each_cleanup_step():
    for step in ("marker_remove", "stop_restore", "surround_restore", "jar_remove"):
        _, state, jar, out, env = setup("retry-" + step, "null", stop=True,
                                        failures={step: "once"})
        result = invoke(state, jar, out, env)
        assert result.returncode == 0, (step, result.stdout, result.stderr)
        report = json.loads((out / "report.json").read_text())
        assert report["result"] == "PASS_CAPTURE_TIMING", (step, report)
        assert report["cleanup"][step]["status"] == "done", (step, report)
        assert len(report["cleanup"][step]["attempts"]) == 2, (step, report)
        assert_clean(state, "null", True, result)


def pending_and_resume_without_absence_proof():
    _, state, jar, out, env = setup("pending-linger", "2", stop=True, linger=True)
    result = invoke(state, jar, out, env)
    assert result.returncode != 0, result.stdout
    report = json.loads((out / "report.json").read_text())
    assert report["result"] == "CLEANUP_PENDING" and report["primary_error"] is None, report
    assert report["cleanup"]["engine_absence"]["status"] != "done", report
    assert report["cleanup"]["stop_restore"]["status"] == "blocked", report
    assert report["cleanup"]["surround_restore"]["status"] == "blocked", report
    interim = json.loads(state.read_text())
    assert interim["surround"] == "1" and not interim["stop"] and interim["jars"], interim
    interim["linger"] = False
    state.write_text(json.dumps(interim))
    resumed = resume(state, out, env)
    assert resumed.returncode == 0, (resumed.stdout, resumed.stderr)
    final = json.loads((out / "report.json").read_text())
    assert final["result"] == "PASS_CAPTURE_TIMING", final
    assert_clean(state, "2", True, resumed)


def independent_cleanup_errors():
    _, state, jar, out, env = setup("pending-jar", "3", failures={"jar_remove": "always"})
    result = invoke(state, jar, out, env)
    assert result.returncode != 0, result.stdout
    report = json.loads((out / "report.json").read_text())
    assert report["result"] == "CLEANUP_PENDING", report
    assert report["cleanup"]["surround_restore"]["status"] == "done", report
    assert report["cleanup"]["jar_remove"]["status"] != "done", report
    before_restore_attempts = {name: len(report["cleanup"][name]["attempts"])
                               for name in ("marker_remove", "engine_absence", "stop_restore",
                                            "surround_restore")}
    fixture = json.loads(state.read_text())
    fixture["failures"] = {}
    fixture["surround"] = "1"
    fixture["stop"] = True
    state.write_text(json.dumps(fixture))
    resumed = resume(state, out, env)
    assert resumed.returncode == 0, (resumed.stdout, resumed.stderr)
    report = json.loads((out / "report.json").read_text())
    after = json.loads(state.read_text())
    assert after["surround"] == "1" and after["stop"] is True, after
    assert not after["jars"] and not after["markers"], after
    assert len(report["cleanup"]["marker_remove"]["attempts"]) > before_restore_attempts["marker_remove"]
    assert len(report["cleanup"]["engine_absence"]["attempts"]) > before_restore_attempts["engine_absence"]
    assert len(report["cleanup"]["stop_restore"]["attempts"]) == before_restore_attempts["stop_restore"]
    assert len(report["cleanup"]["surround_restore"]["attempts"]) == before_restore_attempts["surround_restore"]


def persistent_failures_stay_resumable():
    _, state, jar, out, env = setup("pending-marker", "0", stop=True,
                                   failures={"marker_remove": "always"})
    result = invoke(state, jar, out, env)
    assert result.returncode != 0, result.stdout
    report = json.loads((out / "report.json").read_text())
    assert report["result"] == "CLEANUP_PENDING", report
    assert report["cleanup"]["marker_remove"]["status"] == "failed", report
    assert report["cleanup"]["surround_restore"]["status"] == "blocked", report
    fixture = json.loads(state.read_text()); fixture["failures"] = {}; state.write_text(json.dumps(fixture))
    resumed = resume(state, out, env)
    assert resumed.returncode == 0, (resumed.stdout, resumed.stderr)
    assert_clean(state, "0", True, resumed)

    _, state, jar, out, env = setup("pending-surround", "2",
                                   failures={"surround_restore": "always"})
    result = invoke(state, jar, out, env)
    assert result.returncode != 0, result.stdout
    report = json.loads((out / "report.json").read_text())
    assert report["result"] == "CLEANUP_PENDING", report
    assert report["cleanup"]["surround_restore"]["status"] == "failed", report
    assert report["cleanup"]["jar_remove"]["status"] == "done", report
    fixture = json.loads(state.read_text()); fixture["failures"] = {}; state.write_text(json.dumps(fixture))
    resumed = resume(state, out, env)
    assert resumed.returncode == 0, (resumed.stdout, resumed.stderr)
    assert_clean(state, "2", False, resumed)


def process_death_after_durable_journal():
    _, state, jar, out, env = setup("interrupted", "0")
    script = OUTPUT / "bin/adb"
    original = script.read_text()
    # Pause inside the first mutation after the fake has asserted the journal.
    modified = original.replace("event('push'); s['jars']", "event('push'); time.sleep(60); s['jars']")
    script.write_text(modified)
    proc = subprocess.Popen([sys.executable, str(ROOT / "tools/measure-engine.py"),
                             "--device", "fixture-device", "--jar", str(jar),
                             "--receiver-ip", "192.0.2.20", "--seconds", "1",
                             "--app", "com.amazon.amazonvideo.livingroom", "--output", str(out)],
                            env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, start_new_session=True)
    deadline = time.time() + 8
    while time.time() < deadline:
        fixture = json.loads(state.read_text())
        if fixture.get("events"):
            break
        if proc.poll() is not None:
            raise AssertionError("measurement ended before the blocked push")
        time.sleep(.02)
    else:
        raise AssertionError("fake ADB did not reach push")
    report_path = out / "report.json"
    assert report_path.is_file(), "journal missing after process death point"
    initial = json.loads(report_path.read_text())
    assert initial["result"] == "RUNNING" and initial["original_surround"] == "0"
    os.killpg(proc.pid, signal.SIGKILL)
    proc.wait(timeout=3)
    script.write_text(original)
    resumed = resume(state, out, env)
    assert resumed.returncode == 0, (resumed.stdout, resumed.stderr)
    final = json.loads(report_path.read_text())
    assert final["result"] == "CAPTURE_INTERRUPTED", final
    assert not json.loads(state.read_text()).get("launch_seen"), "resume started capture"
    assert_clean(state, "0", False, resumed)


def keyboard_interrupt_retains_partial_log():
    _, state, jar, out, env = setup("keyboard-interrupt", "1", pause_engine=True)
    proc = subprocess.Popen([sys.executable, str(ROOT / "tools/measure-engine.py"),
                             "--device", "fixture-device", "--jar", str(jar),
                             "--receiver-ip", "192.0.2.20", "--seconds", "1",
                             "--app", "com.amazon.amazonvideo.livingroom", "--output", str(out)],
                            env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True)
    deadline = time.time() + 8
    while time.time() < deadline:
        if json.loads(state.read_text()).get("launch_seen"):
            break
        if proc.poll() is not None:
            raise AssertionError("measurement ended before fake capture blocked")
        time.sleep(.02)
    else:
        raise AssertionError("fake capture did not start")
    proc.send_signal(signal.SIGINT)
    stdout, stderr = proc.communicate(timeout=10)
    assert proc.returncode != 0, (stdout, stderr)
    report = json.loads((out / "report.json").read_text())
    assert report["capture_result"] == "CAPTURE_INTERRUPTED", report
    assert report["primary_error"].startswith("KeyboardInterrupt"), report
    assert report["result"] == "CAPTURE_INTERRUPTED", report
    assert "partial capture log line" in (out / "engine.log").read_text()
    assert_clean(state, "1", False, subprocess.CompletedProcess([], 0, "", ""))


def timeout_keeps_stdout_only_partial_log():
    _, state, jar, out, env = setup("timeout-partial", "0")
    source = ROOT / "tools/measure-engine.py"
    spec = importlib.util.spec_from_file_location("measure_engine_timeout_case", source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    class TimedOutProcess:
        returncode = None

        def __init__(self):
            self.calls = 0

        def poll(self):
            return self.returncode

        def communicate(self, timeout=None):
            self.calls += 1
            if self.calls <= 2:
                raise subprocess.TimeoutExpired("fake app_process", timeout,
                                                output=b"stdout-only partial bytes")
            self.returncode = -15
            return "stdout-only partial bytes", ""

        def terminate(self):
            self.returncode = -15

    real_popen = subprocess.Popen
    timed_process = TimedOutProcess()

    def intercept_popen(command, *args, **kwargs):
        if (len(command) > 4 and command[0] == "adb" and command[3] == "shell"
                and command[4].startswith("CLASSPATH=")):
            return timed_process
        return real_popen(command, *args, **kwargs)

    argv = sys.argv
    old_env = {name: os.environ.get(name) for name in ("PATH", "MEASURE_FIXTURE", "MEASURE_JOURNAL")}
    sys.argv = [str(source), "--device", "fixture-device", "--jar", str(jar),
                "--receiver-ip", "192.0.2.20", "--seconds", "1",
                "--app", "com.amazon.amazonvideo.livingroom", "--output", str(out)]
    os.environ.update({name: env[name] for name in old_env})
    subprocess.Popen = intercept_popen
    try:
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            try:
                module.main()
            except SystemExit as error:
                assert error.code == 1, error.code
            else:
                raise AssertionError("timed out capture unexpectedly passed")
    finally:
        subprocess.Popen = real_popen
        sys.argv = argv
        for name, value in old_env.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value
    report = json.loads((out / "report.json").read_text())
    assert report["capture_result"] == "CAPTURE_FAILED", report
    assert "TimeoutExpired" in report["primary_error"], report
    assert report["cleanup_pending"] == [], report
    assert "stdout-only partial bytes" in (out / "engine.log").read_text()
    assert json.loads(state.read_text())["events"][0] == {"name": "push", "journal_exists": True}
    assert_clean(state, "0", False, subprocess.CompletedProcess([], 0, "", ""))


def owner_checks_and_report_validation():
    for mode in ("active-engine", "active-sender"):
        _, state, jar, out, env = setup(mode, "0")
        result = invoke(state, jar, out, env)
        assert result.returncode != 0 and not out.exists(), (mode, result.stdout)
        assert not json.loads(state.read_text())["jars"], mode
    _, state, jar, out, env = setup("bad-device", "0")
    result = invoke(state, jar, out, env)
    assert result.returncode == 0, (result.stdout, result.stderr)
    before = json.loads((out / "report.json").read_text())
    wrong = resume(state, out, env, "other-device")
    assert wrong.returncode != 0, wrong.stdout
    assert json.loads((out / "report.json").read_text()) == before
    events_before = json.loads(state.read_text()).get("events", [])
    for field, value in (("marker_path", "/data/local/tmp/vban-sender/../../stop"),
                         ("original_surround", "settings put global encoded_surround_output 0")):
        tampered = dict(before); tampered[field] = value
        tampered_path = out / (field + ".json")
        tampered_path.write_text(json.dumps(tampered))
        invalid = resume(state, out, env, report_path=tampered_path)
        assert invalid.returncode != 0, (field, invalid.stdout)
        assert json.loads(state.read_text()).get("events", []) == events_before


def capture_failure_evidence():
    for mode in ("bad-state",):
        _, state, jar, out, env = setup(mode, "0")
        result = invoke(state, jar, out, env)
        assert result.returncode != 0, result.stdout
        report = json.loads((out / "report.json").read_text())
        assert report["capture_result"] == "CAPTURE_FAILED", report
        assert report["result"] == "CAPTURE_FAILED", report
        assert report["primary_error"], report
        assert report["cleanup_pending"] == [], report
        assert (out / "engine.log").is_file()
        assert_clean(state, "0", False, result)
    _, state, jar, out, env = setup("engine-error", "0", failures={"jar_remove": "always"})
    result = invoke(state, jar, out, env)
    assert result.returncode != 0, result.stdout
    report = json.loads((out / "report.json").read_text())
    primary = report["primary_error"]
    assert report["capture_result"] == "CAPTURE_FAILED" and primary, report
    assert report["result"] == "CLEANUP_PENDING" and report["cleanup"]["jar_remove"]["status"] == "failed", report
    fixture = json.loads(state.read_text()); fixture["failures"] = {}; state.write_text(json.dumps(fixture))
    resumed = resume(state, out, env)
    assert resumed.returncode == 0, (resumed.stdout, resumed.stderr)
    report = json.loads((out / "report.json").read_text())
    assert report["result"] == "CAPTURE_FAILED" and report["primary_error"] == primary, report
    assert report["cleanup_pending"] == [], report
    assert_clean(state, "0", False, resumed)
    _, state, jar, out, env = setup("wrong-app", "0")
    result = invoke(state, jar, out, env)
    assert result.returncode != 0 and not out.exists(), (result.stdout, result.stderr)


def main():
    binary = OUTPUT / "bin/adb"
    binary.parent.mkdir(parents=True, exist_ok=True)
    binary.write_text(FAKE_ADB)
    binary.chmod(0o700)
    happy_and_idempotent()
    retries_each_cleanup_step()
    pending_and_resume_without_absence_proof()
    independent_cleanup_errors()
    persistent_failures_stay_resumable()
    process_death_after_durable_journal()
    keyboard_interrupt_retains_partial_log()
    timeout_keeps_stdout_only_partial_log()
    owner_checks_and_report_validation()
    capture_failure_evidence()
    print("PASS: durable journal, cleanup retries, pending resume, no-restore safety gate, owner protection")


if __name__ == "__main__":
    main()
