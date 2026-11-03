# Setup and usage

## Install

The device must provide classic RSA-authorized ADB on TCP port `5555`, reachable
from the app at `127.0.0.1:5555`. USB debugging or Wireless debugging pairing alone
does not provide this connection. The app does not enable ADB for you.
On firmware that supports it, an already authorized USB connection can enable
TCP ADB with `adb -s USB_SERIAL tcpip 5555`; availability after reboot depends
on the device. See [Android's ADB guide](https://developer.android.com/tools/adb).

Set `BOX` to your Android TV device's ADB address. Before updating, select
**OFF** and wait for the engine to stop. Updates signed with the same key preserve
settings and authorization.

```sh
BOX="your-device-address:5555"
adb connect "$BOX"
adb -s "$BOX" install -r build/vban.apk
adb -s "$BOX" shell am start -n app.vbansender/app.vbansender.app.MainActivity
```

In **Settings**, save the receiver's IPv4 address, UDP port, and stream name.
Defaults: port `6980`, stream `vban-audio`, `256` frames per packet.
The receiver address has no default. **Stream name** identifies the audio
stream in VBAN packets and must match the receiver's configuration.

On first setup, open **Local ADB / developer tools → Check local ADB**.
Compare the displayed key fingerprint with the Android authorization dialog
and approve the app's own key. Updates preserve this authorization.

ADB grants shell access; authorize only the app's own key. VBAN sends unencrypted
PCM. Use a trusted network and keep ADB inaccessible from the internet.

## Use

The button shows your choice: green **ON** or dark gray **OFF**, with white text.
A white outline marks remote-control focus. Press to switch. Closing the screen
keeps VBAN enabled; the choice is saved for the next reboot.

| Choice | Foreground app | Audio route |
|---|---|---|
| OFF | Any | Standard Android output |
| ON | Any recognized app except Netflix, including Home | VBAN after stable recognition |
| ON | Netflix / sleeping / unknown app | Standard Android output |

Netflix is bypassed as soon as the app is foreground, including its menus or
paused playback. Your ON choice remains saved. ON does not confirm reception:
there is no receiver discovery or automatic fallback when the receiver goes offline.
Other apps need no individual registration. Their capture compatibility still depends
on the playback app and device firmware.

ON selects stereo PCM, required by the tested Prime capture path. This also
affects HDMI while Netflix is bypassed. OFF restores the exact previous audio
setting. Receiver settings become editable after OFF is confirmed.

## Troubleshooting

Temporary detector timeouts and invalidated audio recorders recover automatically.
Other engine errors appear on the screen and notification after the next status check
(every 15 seconds while the service runs). After an error, switch OFF → ON.
Changing audio routes can interrupt playback;
Prime or Netflix may require restarting the content.
For Prime on the tested Android 12 firmware, enable VBAN before opening the film.
Changing capture while a film is playing can produce an HDMI connection error;
reopen the content after the audio route is stable.
Before updating, uninstalling, or force-stopping the app, select OFF:
Force Stop can leave the detached shell engine running.

Emergency stop through authorized ADB:

```sh
adb -s "$BOX" shell touch /data/local/tmp/vban-sender/stop
```

Then open VBAN and select OFF to restore the audio setting.
On the tested Android 12 firmware, virtual audio capture coincided with repeated
Android TV Remote Service crashes. Network remote controls may become unavailable;
the packet-pacing fix does not resolve that separate compatibility issue.

Developer tools show the last checked engine snapshot, read frames, sent/dropped
packets, and time since audio progress. Silent PCM counts as read data. No data or
IDLE is neutral; these values do not confirm playback or receiver reception.
Stop has a 30-second command/polling budget. If it fails, ownership and saved audio
settings are retained; select OFF again to explicitly retry cleanup.
