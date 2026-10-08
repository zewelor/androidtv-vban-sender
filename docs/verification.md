# Validation and limitations

## Validation scope

Local tests cover packet format and PCM preservation, real UDP delivery, source-clock
pacing, cancellation during waits, foreground routing, ADB authorization/deadlines,
atomic status publication, and serialized ON/OFF races. Run `sh tools/build.sh`
to reproduce them; reports are generated under `build/e2e/`.

Device checks have covered installation, saved receiver settings, local ADB,
ON/OFF, own-process exit, and restoration of audio settings. Earlier playback
checks confirmed short SmartTube/Prime listening sessions and YouTube volume/mute.
A diagnostic subprocess survived reboot, power cycles, and deep sleep.

Diagnostic-process survival does not prove AudioRecord recovery. The daily app's
boot/recovery paths, audio after standby, long playback sessions, A/V synchronization,
and seamless rapid app changes still need broader device validation. Receiver
packet delivery and physical listening are separate checks.

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
