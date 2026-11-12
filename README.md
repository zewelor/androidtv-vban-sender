# VBAN Sender

Stream Android TV audio directly to an existing VBAN receiver over UDP:
48 kHz, stereo, 16-bit PCM.

- One large **ON/OFF** button, a remembered choice, and startup after reboot.
- All recognized foreground apps use VBAN when enabled, except Netflix and Home.
- Netflix and the system's default Home app keep the standard audio output.
- Configure the receiver address once in **Settings**. No receiver discovery.

Requires Android 12+ and authorized classic ADB on the device's TCP port `5555`.
Capture compatibility depends on the device firmware and playback app;
this is an experimental transmitter.
See [known app quirks](docs/daily-app.md#known-app-quirks) if a player fails
when capture is enabled.

[Setup and usage](docs/daily-app.md) ·
[Development](docs/development.md)

## Build

Requires Docker; the pinned image supplies Java and the Android SDK.

```sh
sh tools/build.sh
```

Outputs: `build/vban.apk`, `build/vban-dev.apk`, `build/vban-engine.jar`,
SHA-256 files, and test reports in `build/e2e/`. Use `vban.apk` for normal use.
Download `vban-release.apk` from the latest
[GitHub Release](https://github.com/zewelor/androidtv-vban-sender/releases/latest).
Successful `main` builds also provide it in the `vban-release-build` artifact.
These APKs share a retained signing key. The separate
`vban-test-build` artifacts use temporary test keys; see
[signing and updates](docs/development.md#build-and-artifacts).

## License

[Apache-2.0](LICENSE). Third-party code keeps its original licenses and
[attributions](docs/development.md#third-party-code).
