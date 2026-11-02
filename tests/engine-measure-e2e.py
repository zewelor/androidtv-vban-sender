#!/usr/bin/env python3
"""Run the measurement CLI against scripted ADB outcomes; no Android audio claim."""
import json
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/e2e/engine-measure"
FAKE_ADB = r'''#!/usr/bin/env python3
import hashlib,json,os,pathlib,shlex,sys
p=pathlib.Path(os.environ['MEASURE_FIXTURE'])
s=json.loads(p.read_text())
a=sys.argv[1:]
assert a[:2]==['-s','fixture-device'],a
def save(): p.write_text(json.dumps(s))
if a[2]=='push':
 s['jars'][a[4]]=hashlib.sha256(pathlib.Path(a[3]).read_bytes()).hexdigest();save();sys.exit()
assert a[2]=='shell',a
c=a[3]
if c=='ps -A -o ARGS':
 if s['mode']=='active': print('app_process / app.vbansender.AudioEngine old-run')
elif c=='dumpsys activity activities':
 app='com.other.app' if s['mode']=='wrong-app' else 'com.amazon.amazonvideo.livingroom'
 print('mResumedActivity: ActivityRecord{fixture u0 '+app+'/.MainActivity t1}')
elif c=='settings get global encoded_surround_output': print(s['surround'])
elif c.startswith('if [ -e '): print('PRESENT' if s['stop'] else 'ABSENT')
elif c=='date +%s': print(1793606400)
elif c.startswith('sha256sum '): print(s['jars'][shlex.split(c)[1]]+'  jar')
elif c.startswith('mkdir -p '): pass
elif c.startswith('settings put global encoded_surround_output '):
 s['surround']=c.split()[-1];save()
elif c=='settings delete global encoded_surround_output': s['surround']='null';save()
elif c.startswith('touch '):
 path=shlex.split(c)[1]
 if path.endswith('/stop'): s['stop']=True
 else: s['markers'].append(path)
 save()
elif c.startswith('CLASSPATH='):
 t=shlex.split(c);run=t[8]
 s['final']='run='+run+' uid=2000 pid=123 state='+('ERROR' if s['mode']=='bad-state' else 'STOPPED')+' message=none'
 save()
 if s['mode']=='engine-error': print('fixture engine failed',file=sys.stderr);sys.exit(7)
 print(json.dumps({'state':'CAPTURE_ENDED','frames':256,'sent':1,'dropped':0,'peak':1}))
elif c=='cat /data/local/tmp/vban-sender/engine-state.txt': print(s['final'])
elif c.startswith('logcat '):
 print('1793600000.000 1 1 W AudioFlinger: RecordThread: buffer overflow')
 print('1793606400.500 1 1 W AudioFlinger: RecordThread: buffer overflow')
elif c.startswith('rm -f '):
 path=shlex.split(c)[2]
 s['jars'].pop(path,None)
 if path in s['markers']: s['markers'].remove(path)
 save()
else: raise RuntimeError('Unexpected fixture command: '+c)
'''


def scenario(mode, original="0", stop=False):
    directory = OUTPUT / (mode + ("-null" if original == "null" else ""))
    directory.mkdir(parents=True, exist_ok=True)
    state = directory / "state.json"
    state.write_text(json.dumps({"mode": mode, "surround": original, "stop": stop,
                                 "markers": [], "jars": {}}))
    jar = directory / "fixture.jar"
    jar.write_bytes(b"synthetic measurement artifact")
    result_path = directory / "result"
    # Each subprocess invocation must use a new evidence directory.
    if result_path.exists():
        import shutil
        shutil.rmtree(result_path)
    env = dict(os.environ, MEASURE_FIXTURE=str(state),
               PATH=str(OUTPUT / "bin") + os.pathsep + os.environ["PATH"])
    result = subprocess.run([sys.executable, str(ROOT / "tools/measure-engine.py"),
                             "--device", "fixture-device", "--jar", str(jar),
                             "--receiver-ip", "192.0.2.20", "--seconds", "1",
                             "--app", "com.amazon.amazonvideo.livingroom",
                             "--output", str(result_path)], env=env,
                            capture_output=True, text=True, timeout=10)
    after = json.loads(state.read_text())
    assert after["surround"] == original, (mode, after, result.stderr)
    assert not after["jars"] and not after["markers"], (mode, after)
    if mode == "ok":
        assert result.returncode == 0, (result.stdout, result.stderr)
        report = json.loads((result_path / "report.json").read_text())
        assert report["result"] == "PASS_CAPTURE_TIMING"
        assert report["nonzero_pcm_observed"] is True
        assert len(report["audio_errors"]) == 1, report
        assert after["stop"] is stop
    else:
        assert result.returncode != 0, (mode, result.stdout)
        if mode in ("engine-error", "bad-state"):
            report = json.loads((result_path / "report.json").read_text())
            assert "result" not in report, report
        else:
            assert not result_path.exists(), mode


def main():
    binary = OUTPUT / "bin/adb"
    binary.parent.mkdir(parents=True, exist_ok=True)
    binary.write_text(FAKE_ADB)
    binary.chmod(0o700)
    scenario("ok")
    scenario("ok", "null", stop=True)
    for mode in ("engine-error", "bad-state", "wrong-app", "active"):
        scenario(mode)
    print("PASS: measurement cleanup, original settings, failed engine, bad status, wrong app, active owner")


if __name__ == "__main__":
    main()
