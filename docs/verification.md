# Validation and limitations

## Validation scope

Local tests cover packet format and PCM preservation, real UDP delivery and receiver
outage recovery, source-clock pacing, cancellation during waits, foreground routing,
ADB authorization/deadlines, atomic status publication, and serialized ON/OFF races.
Run `sh tools/build.sh`
to reproduce them; reports are generated under `build/e2e/`.

Device checks have covered installation, saved receiver settings, local ADB,
ON/OFF, own-process exit, and restoration of audio settings. Earlier playback
checks confirmed short SmartTube/Prime listening sessions and YouTube volume/mute.
A diagnostic subprocess survived reboot, power cycles, and deep sleep.

Version 0.6 completed one standby cycle longer than seven minutes on the tested
Android 12 device. Capture paused during sleep and resumed with the same engine PID
and run after physical wake. UDP reception and audible SmartTube playback returned
without toggling VBAN or restarting the app. A signed update also preserved settings
and local ADB authorization.

Version 0.7 was updated with the same signing key and preserved settings and ADB
authorization. A controlled engine exit appeared as an error in the app after about
13 seconds. OFF → ON restored sending, confirmed by receiver packets. Detector
and recorder recovery are covered by synthetic fault tests, not injected Android
system failures.

Version 0.8 adds regression coverage for delayed toggle delivery, OFF during health
checks, service replacement, and failed cleanup. The full local build passed;
service lifecycle adapters and scripted shell failures exercise production logic,
but do not prove Android process-death or lifecycle timing. The signed update
preserved receiver settings and local ADB authorization on the tested device.
OFF restored the original audio setting; rapid OFF → ON replaced the engine with
one new process and resumed SmartTube sending. A receiver capture confirmed 30
UDP packets with no capture drops. Physical listening and another standby cycle
were not checked in this update.

The daily app's boot/recovery paths, overnight standby, long playback sessions,
A/V synchronization, and seamless rapid app changes still need broader validation.
Receiver packet delivery and physical listening are separate checks.

## Compatibility

Netflix capture failed on the tested Android 12 firmware with `tvq-pb-101`,
`getOutputForAttr ... -38`, and AudioTrack initialization errors, including when
capture preceded playback. Netflix therefore uses the standard output.

Observed DIRECT/HW_AV_SYNC requirements are consistent with
[Android 12 AudioPolicyManager](https://android.googlesource.com/platform/frameworks/av/+/android-12.0.0_r1/services/audiopolicy/managerdefault/AudioPolicyManager.cpp),
but the exact vendor HAL failure was not established. System privileges or a newer
Android version do not guarantee compatible, permitted capture. No DRM, screenshot
protection, or firmware modifications are part of this project.

REMOTE_SUBMIX also triggered an Android TV Remote service error,
`Illegal output device type 25`, on tested firmware. Physical remote controls
worked in earlier checks; phone-based remote compatibility is unverified.
Audio routing changes can interrupt a player and require restarting the content.

Pacing avoids application-side packet bursts and growing queues; it does not
guarantee fixed end-to-end latency or uninterrupted audio under overload.
No measured CPU or latency improvement is claimed for the simplification changes.
