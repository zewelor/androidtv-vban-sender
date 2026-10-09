# Development

## Build and artifacts

`sh tools/build.sh` compiles Java to DEX and packages signed APKs using a pinned
SDK 36 image, with minimum/target API 31. Tests have no external network access;
the first run may require downloading the Docker image.

`vban.apk` is the normal build. `vban-dev.apk` enables debugging and `run-as`.
Both share package `app.vbansender` and the signing key, so they replace one another.
Select OFF before switching variants.

The local test signing key is generated in `build/signing/proof.p12`.
Keep it for updates to your own installation; it is not a production release key.
Each fresh CI job generates a different signing key. Its APKs are test artifacts,
stored as `vban-test-build`. Pushes and manual builds on `main` additionally produce
`vban-release-build/vban-release.apk` with the retained release key. Pull requests
and other branches do not receive release secrets. The developer APK stays test-signed.
Changing from a test signature to the release signature requires one reinstall and
reauthorization. Later release builds preserve app data when installed as updates.
Increase `versionCode` and `versionName` in the manifest for each published version.
The app generates its ADB key in private app storage, independently of desktop ADB.
CI uploads only APK/JAR, checksums, and synthetic test results. Device diagnostics,
ADB keys, and signing keys remain outside published artifacts.

### Release signing

Generate one private PKCS12 keystore with alias `release`, a strong password, and
at least 25 years of certificate validity. Keep the keystore and password in a
private backup outside this repository. Never regenerate the key for later releases.
Set repository Actions secrets `RELEASE_KEYSTORE_BASE64` (base64 of the keystore)
and `RELEASE_KEYSTORE_PASSWORD` (its password). The workflow reconstructs them in
a temporary directory and removes it after signing; neither file is uploaded.
The release certificate is identified by its public SHA-256 fingerprint in CI logs.

To sign locally after a successful build:

```sh
sh tools/sign-release.sh /private/path/release.p12 /private/path/password.txt
```

This produces `build/vban-release.apk` and its checksum. Actions artifacts expire
after seven days; release signing does not publish a GitHub Release or add an app updater.

## Runtime

The app starts `AudioEngine` as shell UID through authorized ADB on `127.0.0.1:5555`.
AudioRecord/REMOTE_SUBMIX → PCM S16LE, 48 kHz stereo → VBAN/UDP.
ADB carries control commands only. No root, receiver-side service, wake lock,
watchdog, or recording of captured content is required.

A local foreground observer allows capture after one second of stable recognition
of SmartTube, stock YouTube, or Prime. Netflix, sleeping state, and unknown/stale
observations prevent capture. Detection can occur after a player creates AudioTrack,
so seamless app transitions are not guaranteed.

The app serializes ON/OFF requests. The shell engine owns the capture lock;
a per-run marker fences delayed starts after OFF. Atomic status includes run, UID,
PID, state, and error. OFF confirms own-process exit and restores the saved surround
setting. Boot and reopening the UI also reconcile interrupted cleanup.
Failures remain visible without a restart loop.

One routing loop supports finite probes and continuous operation. Packet pacing
follows the source clock within a bounded 1024-frame read, without catch-up bursts
or an application queue. The capture buffer is twice the platform minimum, with
a minimum of one read block. Normal operation skips diagnostic sample statistics.
Temporary UDP port-unreachable notifications discard packets without stopping capture;
unexpected socket and capture failures remain visible. Lost audio is not replayed.
A foreground-detection timeout releases capture and retries; a fresh supported-app
observation must become stable before capture resumes. Permission errors remain fatal.
`AudioRecord.ERROR_DEAD_OBJECT` releases and recreates the recorder after a cancellable
one-second delay, with at most three retries when failures recur within ten seconds.
Persistent recorder failures and other capture errors remain fatal. See the
[AudioRecord API](https://developer.android.com/reference/android/media/AudioRecord#ERROR_DEAD_OBJECT).
The service checks engine status every 15 seconds on the Start/Stop worker and
when the app is reopened. Checks report failures without restarting a failed engine;
a successful check clears a temporary status-read error.

## Diagnostics and tests

Use your exact ADB device address and existing receiver configuration:

```sh
python3 tools/device.py --device "$BOX" inspect
python3 tools/device.py --device "$BOX" capture \
  --destination "$RECEIVER_IP" --port 6980 --stream vban-audio --seconds 30
python3 tools/device.py --device "$BOX" stop
adb -s "$BOX" shell am start -n app.vbansender/app.vbansender.app.ProofActivity --ei proof_seconds 10
```

`capture-probe.py` and `pilot-probe.py` measure local UDP packets/levels using
`--listen-ip`; they do not play audio. `device.py tone` sends a quiet test tone;
`app-aware` runs a finite routing probe. Do not run probes while the APK is ON.
`boot-probe.py --reboot` reboots the specified device; `--cold-power-cycle`
only observes a physical power cycle performed after READY. Use `--help` for options.
Device reports stay in `build/device/`; review them before sharing.

The full build checks protocol golden samples, real UDP, a synthetic TCP ADB peer,
pacing, routing, boot observation, and controller races. Reproducible reports are
in `build/e2e/`. These tests do not replace device playback and listening checks.

## Third-party code

The project is licensed under [Apache-2.0](../LICENSE).
`ShellContext.java` adapts the small `FakeContext` wrapper from
[scrcpy v3.3.4](https://github.com/Genymobile/scrcpy/tree/fb6381f5b9bb96f3fa823d899f4c32de2ec84ab3)
(Apache-2.0; `third-party-scrcpy-LICENSE`). The client uses
[vendored AdbLib and documented fixes](../vendor/adblib/README.md) (BSD-3-Clause).
This includes its Java ADB transport and authentication implementation, with four
local transport fixes. Original notices remain in the repository, APKs, and JAR.
