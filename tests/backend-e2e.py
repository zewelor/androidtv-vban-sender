#!/usr/bin/env python3
"""Actual shell-backend cleanup, with failed command transport and saved settings."""
import importlib.util
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/e2e/backend"


def main():
    spec = importlib.util.spec_from_file_location("service_adapters", ROOT / "tests/service-e2e.py")
    adapters = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(adapters)
    stubs = dict(adapters.STUBS)
    del stubs["app/vbansender/app/EngineBackend.java"]
    stubs.update({
        "com/cgutman/adblib/AdbCrypto.java": "package com.cgutman.adblib; public class AdbCrypto { }",
        "app/vbansender/app/SelfAdbProof.java": "package app.vbansender.app; public class SelfAdbProof { public static com.cgutman.adblib.AdbCrypto keys(android.content.Context c){return new com.cgutman.adblib.AdbCrypto();} }",
        "app/vbansender/LocalAdb.java": "package app.vbansender; public class LocalAdb { public static String shell(com.cgutman.adblib.AdbCrypto c,int port,String cmd,int timeout)throws Exception{return app.vbansender.app.BackendCleanupHarness.command(cmd);} }",
    })
    source, classes = OUT / "adapters", OUT / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    for name, contents in stubs.items():
        path = source / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents)
    actual = ["app/AudioController.java", "app/EngineBackend.java", "app/AppConfig.java",
              "app/EngineState.java", "OutputConfig.java", "VbanPacket.java"]
    subprocess.run(["javac", "--release", "8", "-d", str(classes)]
                   + [str(p) for p in source.rglob("*.java")]
                   + [str(ROOT / "src/app/vbansender" / p) for p in actual]
                   + [str(ROOT / "tests" / p) for p in ("BackendCleanupHarness.java", "MemoryPrefs.java")], check=True)
    result = subprocess.run(["java", "-cp", str(classes), "app.vbansender.app.BackendCleanupHarness"],
                            capture_output=True, text=True, timeout=15)
    report = {"result": "PASS" if result.returncode == 0 else "FAIL", "stdout": result.stdout,
              "stderr": result.stderr, "scope": "Production backend/preferences; scripted shell transport, no device proof"}
    (OUT / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    assert result.returncode == 0, report
    print("PASS: persisted failed cleanup, replacement guard, restored settings, reconciliation")


if __name__ == "__main__":
    main()
