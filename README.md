# VBAN Sender

Stream Android TV audio directly to an existing VBAN receiver over UDP:
48 kHz, stereo, 16-bit PCM.

- One large **ON/OFF** button, a remembered choice, and startup after reboot.
- SmartTube, YouTube, and Prime use VBAN when enabled.
- Netflix and unsupported apps keep the standard audio output.
- Configure the receiver address once in **Settings**. No receiver discovery.

Requires Android 12+ and authorized classic ADB on the device's TCP port `5555`.
Capture compatibility depends on the device firmware and playback app;
this is an experimental transmitter.

[Setup and usage](docs/daily-app.md) ·
[Development](docs/development.md) ·
[Validation and limitations](docs/verification.md)

## Build

Requires Docker; the pinned image supplies Java and the Android SDK.

```sh
sh tools/build.sh
```

Outputs: `build/vban.apk`, `build/vban-dev.apk`, `build/vban-engine.jar`,
SHA-256 files, and test reports in `build/e2e/`. Use `vban.apk` for normal use.
On GitHub, use `vban-release.apk` from the `vban-release-build` artifact of a
successful `main` build. These APKs share a retained signing key. The separate
`vban-test-build` artifacts use temporary test keys; see
[signing and updates](docs/development.md#build-and-artifacts).

## License

[Apache-2.0](LICENSE). Third-party code keeps its original licenses and
[attributions](docs/development.md#third-party-code).
