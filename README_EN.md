# BirdMic

[Deutsch](README.md)

**An old Android phone as a Wi-Fi microphone for BirdNET-Pi.**

BirdMic turns a retired smartphone into an RTSP audio server. It publishes the microphone **uncompressed** on the network (PCM L16, 48 kHz), so species recognition works on the actual signal rather than codec artefacts.

```
rtsp://192.168.1.42:8554/audio.wav
```

Built for continuous operation: foreground service, Wakelocks, a recording watchdog, and autostart after reboot.

---

## Contents

- [What the app can do](#what-the-app-can-do)
- [Requirements](#requirements)
- [Installation](#installation)
- [Initial setup](#initial-setup)
- [Connect BirdNET-Pi](#connect-birdnet-pi)
- [All settings](#all-settings)
- [Understanding the displays](#understanding-the-displays)
- [Which microphone is used?](#which-microphone-is-used)
- [24/7 operation checklist](#247-operation-checklist)
- [Quality and power consumption](#quality-and-power-consumption)
- [Troubleshooting](#troubleshooting)
- [Technical details](#technical-details)
- [Build it yourself](#build-it-yourself)
- [Access protection, mDNS, log](#access-protection-mdns-log)
- [Web interface](#web-interface)
- [New versions](#new-versions)
- [Record several devices at once](#record-several-devices-at-once)
- [Known limitations](#known-limitations)
- [Code structure](#code-structure)
- [Ideas for later](#ideas-for-later)
- [Licence](#licence)

Changes per version: [CHANGELOG.md](CHANGELOG.md)

---

## What the app can do

### Audio

- **Uncompressed PCM L16**, 48 kHz, 16 bit — lossless, with no codec in between
- **Mono or stereo**; stereo automatically falls back to mono if the device cannot support it
- **Recording-source selection** from six variants, the only Android mechanism that can influence which physical microphone the driver supplies
- **Input-device selection** (internal, USB, jack, Bluetooth); attached external microphones are preferred automatically
- **AGC, noise suppression, and echo cancellation are actively disabled** because Android speech processing pumps quiet calls and removes broadband information used by BirdNET
- **Digital preamplifier** from 0.1× to 20×; **high-pass filter** against wind, traffic, and building vibration (80–300 Hz, 24 dB/octave)
- **Selectable sample rate** from 16 to 48 kHz and the actual active microphone, including position, from Android 9 onwards

### Network

- **RTSP 1.0 server** with `OPTIONS`, `DESCRIBE`, `SETUP`, `PLAY`, `PAUSE`, `TEARDOWN`, and `GET_PARAMETER`
- **RTP over UDP and TCP interleaved**, compatible with `ffmpeg -rtsp_transport tcp` and standard UDP clients
- **RTCP Sender Reports** every five seconds, multiple recipients with their own sessions, configurable port and path
- Optional **Digest authentication** (RFC 2617), `_rtsp._tcp` mDNS advertisement, dead-UDP-session timeout, and TCP keepalive
- Compatible with ffmpeg, ffplay, VLC, GStreamer, BirdNET-Pi, and BirdNET-Go

### Continuous operation

- **Foreground service** of type `microphone`, a recording watchdog with backoff (1 s to 30 s), and a restart counter
- **Partial Wakelock and Wi-Fi high-performance lock**, optional autostart after reboot, and direct access to battery optimization
- Battery-temperature monitoring, a warning when external power is missing, and a timestamped log of the last 100 events
- Quick Settings tile and no allocations in the audio path

### Interface

- Live root-scaled **level meter**, peak hold and clipping counter, and live **spectrogram**
- Recipient count, addresses and transport, runtime, transferred data, and battery state
- Save a diagnostic report as a text file; optional read-only web interface; copy the RTSP address
- Persistent notification with recipient count, warnings, and a stop button

---

## Requirements

| | |
| --- | --- |
| Android | 8.0 (API 26) to 14; tested on 10 and 11 |
| Network | Wi-Fi, on the same network as the Pi |
| Power | Permanently connected to a charger |
| Recipient | BirdNET-Pi, BirdNET-Go, or any RTSP client |

### Devices under test

As of 19 August 2026, a comparison test is running with three devices in the same position and recording synchronously:

| Device | Android | Note |
| --- | --- | --- |
| HUAWEI POT-LX1 | 10 | |
| HMD Global Nokia 2.3 | 11 | |
| Samsung Galaxy S7 | 10 | Custom ROM on S9 firmware; second battery — exactly the use case for which the app is intended |

All three entries were read from the app (diagnostic report or web-interface heading), not derived from datasheets. The test field therefore covers **two**, not three, Android versions; Android 10 is represented twice, once stock and once as a custom ROM on nine-year-old hardware.

Results — restart count, clipping, and which source reaches the lower microphone — will be added when available. Recording and comparison use the programs in [Record several devices at once](#record-several-devices-at-once). A custom ROM is explicitly welcome: reliable operation there says more about robustness than three stock devices.

### Why not older than Android 8?

The lower limit is not convenience. It is the point at which the app is allowed to do what it does:

| Since | Available from then |
| --- | --- |
| 5.0 (21) | `AudioRecord.setPreferredDevice` — without it there is no device selection |
| 6.0 (23) | Runtime permissions |
| 8.0 (26) | **Notification channels and `startForegroundService`** |
| 9.0 (28) | `getActiveMicrophones()`, `AudioDeviceInfo.getAddress()` |

Android 8.0 is the decisive step: without a foreground service with a channel, Android freezes an app after minutes. Android 4.3 could be made to run the code, but every foreground-service, permission, and device-selection line would need an untestable second path. Devices from 2013 are not suitable for a 24/7 Wi-Fi stream and their batteries are twelve years old.

At the upper end, `targetSdk 34` (Android 14) is the strictest target against which the app is built. Its actual restriction — a microphone foreground service may no longer start from `BOOT_COMPLETED` — is handled; see [Autostart and Android 14](#autostart-and-android-14). On Android 15 and 16 an app targeting 34 continues to run; it is simply not yet subject to the newer rules.

---

## Installation

### Ready-made APK

The finished APK is available under [Releases](https://gitlab.schanz.ipv64.net/chance-konstruktion/birdmic-rtsp/-/releases). Download it directly:

```bash
curl -LO https://gitlab.schanz.ipv64.net/api/v4/projects/5/packages/generic/birdmic/2.8/BirdMic-2.8.apk
```

It is a release build signed with the debug key (APK Signature Scheme v2).

> **2.8 is the tested version.** Since its release on 04 September 2026 it has run continuously on a Nokia 2.3 (Android 11) as a permanent station. There is no separate fallback at the repository root; all previous versions, including 1.2, are under [`archiv/`](archiv/).

1. Copy the APK to the phone and tap it.
2. Allow installation from unknown sources for the file manager.
3. Install it.

Or use ADB:

```bash
adb install -r BirdMic-2.8.apk
```

> Debug signing is sufficient for a device on your own Wi-Fi. The Play Store would require a dedicated keystore.

---

## Initial setup

1. **Open the app** and grant microphone permission.
2. Tap **“Disable battery optimization”** and confirm. This is by far the most frequent reason for nightly interruptions; do not skip it.
3. Select the **recording source**. The default is “Automatic”; for the lower microphone choose “MIC (main microphone)”. See [Which microphone is used?](#which-microphone-is-used).
4. Tap **“Start stream”**. The displayed address is what the Pi needs.
5. **Test from the Pi before** reconfiguring BirdNET:

   ```bash
   ffmpeg -rtsp_transport tcp -i rtsp://PHONE-IP:8554/audio.wav -t 10 test.wav
   ```

   If it produces a usable ten-second file, everything is correct. To listen:

   ```bash
   ffplay -rtsp_transport tcp -fflags nobuffer rtsp://PHONE-IP:8554/audio.wav
   ```

6. **Assign a fixed IP** with a DHCP reservation in the router. Otherwise the Pi configuration will eventually point nowhere. Fritzbox: *Home Network → Network → Device → “Always assign this network device the same IPv4 address”*.
7. **Turn off the display**, connect the phone to the charger, and you are done.

---

## Connect BirdNET-Pi

In the web interface, set `RTSP_STREAM` under **Settings → Advanced**:

```
rtsp://192.168.1.42:8554/audio.wav
```

Then restart the services through *Tools → Restart Services* or:

```bash
sudo systemctl restart birdnet_recording.service
```

### Extraction Audio Format

This setting has **nothing** to do with BirdMic and **does not affect recognition**: BirdNET analyses the stream before anything is stored. It affects only the evidence clips on disk.

| Format | 3-second clip | Lossless |
| --- | --- | --- |
| WAV | ~290 KB | ✅ |
| **FLAC** | **~150 KB** | **✅** |
| MP3 | ~50 KB | ❌ |
| Opus | ~25 KB | ❌ |

**Recommendation: FLAC.** It is lossless like WAV and half the size. WAV provides no advantage over FLAC and fills the SD card twice as fast: 1,000 detections per day are roughly 9 GB per month instead of 4. For a small card (≤ 32 GB), MP3 is the sensible choice.

---

## All settings

Selection fields are locked while the stream is running. **Stop it first, change the setting, then start it again.**

| Setting | Default | Meaning |
| --- | --- | --- |
| **Recording source** | Automatic | Indirectly determines which physical microphone is used. Six variants; see below. |
| **Input device** | Automatic | Selects a specific device. Reliable for USB/jack; internal microphones usually report only one common entry, so source selection is the only option. |
| **Channels** | Mono | Stereo doubles bandwidth. BirdNET analyses mono anyway; leave it at mono. |
| **Port** | 8554 | 1024–65535. |
| **Path** | `audio.wav` | Cosmetic only. The server provides the same track at **every** path; the name says nothing about the format. |
| **Gain** | 1.0 | Digital preamplifier, 0.1–20. Use above 1.0 only in a very quiet environment or clipping may result. |
| **Sample rate** | 48000 Hz | 48, 44.1, 32, 22.05, or 16 kHz. **Leave it at 48 kHz**; lower rates remove upper frequencies used by BirdNET. Change only if the device cannot do 48 kHz. |
| **High-pass filter** | off | 80–300 Hz, 24 dB/octave. See below. |
| **mDNS** | off | Advertises the stream as `_rtsp._tcp` on the network. |
| **Web interface** | off | Read-only browser view. See below. |
| **Web interface port** | 8080 | Must differ from the RTSP port. |
| **UDP timeout** | 0 (off) | Seconds without a sign of life before a UDP session is discarded. Applies **only** to UDP; TCP is detected through connection closure. |
| **Max. recipients** | 4 | Limit for simultaneous recipients; 0 = unlimited. When exceeded, the oldest gives way. Since 2.5 it is checked at connection setup as well as at `PLAY`. |
| **Max. per address** | 2 | The same limit per source IP. Protects against recipients multiplying after a connection error. |
| **Access protection** | off | Username + password for Digest authentication. Both empty = no protection. |
| **Autostart** | on | Starts again after reboot, but only if the stream was running at shutdown. |
| **Battery warning** | 40 °C | Battery-temperature warning threshold; 0 = off. |
| **Check for new version** | on | Queries the project GitLab on opening, at most every six hours. It only reports; download and installation are manual. |
| **Always dark** | off | True AMOLED black instead of the system dark theme. **Does not save power** (the display is off in operation); helps reading at dusk and prevents burn-in. |

### High-pass filter

Below about 150 Hz, an outdoor recording is almost entirely interference: wind, traffic, and building vibration. It consumes headroom and contributes nothing to recognition; the lowest call of a native bird, the Eurasian eagle-owl, is around 250 Hz.

| Setting | Use |
| --- | --- |
| Off | Default. Nothing is changed. |
| 80 Hz | Very cautious; only against mains hum and the lowest vibration. |
| **120–150 Hz** | **The usual case.** Removes wind and traffic while leaving all bird calls untouched. |
| 200–300 Hz | Only with strong interference. At 300 Hz it can cut into eagle-owl calls. |

Start with 150 Hz and check the result in the app’s spectrogram: the lower edge should become dark without anything disappearing above it.

### What cannot be configured

- **Bit depth:** fixed at 16 bit. Phone microphones do not provide more.
- **Codec:** none, deliberately. See [Quality and power consumption](#quality-and-power-consumption).

---

## Understanding the displays

| Display | Meaning |
| --- | --- |
| **Status line** | `Stopped`, `Ready — waiting for connection`, `Streaming to N recipients`, or an error message |
| **Address** | The RTSP URL: a preview before start, then the actual address. |
| **Detail line** | Source in use, sample rate, channels, device. |
| **Active microphone** | What the system actually reports, including position, from Android 9 onwards. **Use it to verify that source selection produces the intended result**, rather than guessing. |
| **mDNS advertised as** | The name under which the service is visible on the network. |
| **⚠ Recording restarted N×** | The watchdog had to intervene. Still 0 after days means stable; an increasing number means a power-saving mechanism is still acting. |
| **Level meter** | Root-scaled RMS. Bird calls are often only a few percent, hence the expanded lower range. |
| **Peak N %** | Highest excursion with hold. It should stay below 100% during loud events. |
| **Clipped** | Counter of truncated samples. **A number here means the gain is too high** and destroys precisely the loud close recordings that would otherwise provide the best recognition. |
| **Spectrogram** | Live view of frequencies up to 16 kHz, high tones at the top. Use it to check placement, interference sources, and filter action. |
| **🔊 N recipients connected** | Number, IP address, and transport (TCP/UDP) of every recipient. |
| **Runtime / sent / battery** | Time since start, transferred data, temperature, and charging state. |
| **Log** | Recent timestamped events: start, stop, connections, watchdog interventions, and warnings. |

---

## Which microphone is used?

Android has **no** API for “use the lower microphone”. Which built-in microphone the driver provides depends on the selected **AudioSource**; the manufacturer defines this mapping in the audio HAL. The table describes typical cases, not a guarantee:

| Source | Usually | Processing |
| --- | --- | --- |
| Automatic | upper microphone | none |
| UNPROCESSED (raw) | upper reference microphone | none |
| **MIC (main microphone)** | **lower microphone** | minimal |
| **Phone microphone** | **lower microphone** | AEC + noise filter, often impossible to disable |
| Speech recognition | varies | optimized for speech |
| Camera microphone | rear | optimized for video |

**For the lower microphone, try “MIC (main microphone)” first.** On most devices it is the phone-call microphone and mostly keeps post-processing out of the signal path.

“Phone microphone” (`VOICE_COMMUNICATION`) reaches the lower microphone even more reliably, but enables driver echo cancellation and noise filtering that the API cannot always disable. That is a noticeable disadvantage for bird recordings and should be the second choice only.

**An indication instead of guessing:** from Android 9, the app displays what `AudioRecord.getActiveMicrophones()` reports under “Active microphone”, including position. This is the system’s report, not necessarily the complete physical signal path; some devices report several capsules or imprecise positions. Finally rely on a listening test: make a short `ffmpeg` recording after every change.

---

## 24/7 operation checklist

- [ ] Battery optimization disabled for BirdMic
- [ ] **Manufacturer app killer disabled**; these ignore the default setting:
      - Xiaomi/Redmi: allow *Autostart* **and** select *No restrictions*
      - Huawei/Honor: *Manage app launch manually*
      - Samsung: add it to *Unoptimized apps*
      - OnePlus/Oppo: disable *Advanced optimization*
- [ ] Fixed IP through DHCP reservation
- [ ] Autostart enabled in the app (**ineffective on Android 14+**, see below)
- [ ] Display off, phone connected to charger
- [ ] Check restart counter and log after a few days
- [ ] Leave the battery warning at 40 °C
- [ ] Enable an 80% charging limit if the device supports it

### Autostart and Android 14

**Autostart does not work on Android 14 and newer.** This is not negligence, but a system restriction: apps targeting API 34 or higher may **not start a foreground service of type `microphone`** from `BOOT_COMPLETED`. The restriction applies one Android version earlier for microphone service types than for the other types. It cannot be bypassed.

| Android | Autostart |
| --- | --- |
| 8–13 | works |
| 14+ | **blocked** — the app displays a notification instead; tapping it starts the stream |

For a permanently installed device, that means someone must tap once after a power failure on Android 14+. On an old Android 8–11 device — the typical case for this project — the restriction does not apply.

> **Hardware warning:** A phone that remains at 100% on the charger and becomes warm can develop a swollen battery over months. This is a real fire-risk factor with old devices. Do not install it on a flammable surface and inspect the battery occasionally.

---

## Quality and power consumption

### Is the app energy-efficient?

**No — and it cannot be.** There is a real trade-off:

| | Consumption | Quality |
| --- | --- | --- |
| Uncompressed L16 (this app) | ~1.5–3 W | lossless |
| Compressed Opus/AAC | ~0.8–1.5 W | artefacts in the bird-frequency range |

Consumption is almost entirely the **permanently awake Wi-Fi chip** and 770 kbit/s, not audio processing, which costs only a few percent CPU. A Wakelock around the clock is the opposite of energy-efficient.

Because the device is connected to power, this is practically irrelevant: 2–3 W over a year is about 20 kWh, roughly €7. Within that constraint, every avoidable cost is avoided: no polling, allocations, or idle wakeups.

### Is this maximum quality?

For **transmission**, yes: uncompressed 16 bit / 48 kHz, an unprocessed source, and disabled effects. That is also what the built-in hardware of almost all phones delivers through `AudioRecord`. Individual devices can do more; USB audio interfaces can support 24 bit and higher rates, but this app does not currently use that.

**The bottleneck after that is the microphone itself.** A built-in MEMS microphone is designed for speech at 20 cm: limited signal-to-noise ratio, a wind high-pass filter, and little sensitivity to quiet calls at 30 m.

**The largest improvement is therefore not a software parameter but an external microphone at the USB port.** The app takes it automatically. A simple USB lavalier helps more than any setting here.

### Bandwidth

| Configuration | Data rate |
| --- | --- |
| 48 kHz mono | ~770 kbit/s |
| 48 kHz stereo | ~1.5 Mbit/s |

Unproblematic on Wi-Fi, but unsuitable for a mobile connection.

---

## Troubleshooting

| Symptom | Cause and solution |
| --- | --- |
| Stream stops at night | Battery optimization or manufacturer app killer; see checklist. |
| `Connection refused` | Incorrect IP or port, or the stream is not started. Check the address in the app. |
| Stream runs, but silence | Wrong microphone covered or gain too low. Watch the level meter. |
| Distorted, crackling sound | Gain too high. Return to 1.0. |
| Pi cannot find stream after reboot | IP changed. Set a DHCP reservation. |
| “Port 8554 in use” | Another app uses the port. Choose another port and adjust `RTSP_STREAM`. |
| Restart counter rises | Another app accesses the microphone or a power-saving mechanism is active. |
| Stereo selected, display says mono | The device cannot record stereo. Automatic fallback, not an error. |
| Wrong microphone (upper instead of lower) | Change recording source; see above. |
| Recognition rate worse than expected | Usually the microphone or location, not transmission. Check spectrograms in the BirdNET web interface. |
| “Clipped” rises | Lower gain. Every counted sample is truncated signal. |
| Spectrogram stays black | Stream is not running, or it really is very quiet. Check the level meter. |
| Continuous bright band at bottom of spectrogram | Rumble or wind. Set high-pass filter to 150 Hz. |
| Client gets 401 Unauthorized | Access protection is active. Put credentials in the URL: `rtsp://user:password@ip:8554/audio.wav` |
| Tile does not react | Without microphone permission it only opens the app. Start once in the app. |
| Web interface unavailable | It runs only while the stream runs. Otherwise the port may be occupied; this is recorded in the log. |
| Web interface shows old values | Reload the page. Responses use `Cache-Control: no-store`; an intermediary cache should not apply. |
| Browser spectrogram stops | The tab is in the background; it deliberately pauses there. |

### Reading logs

```bash
adb logcat -s BirdMic/Audio BirdMic/Rtsp BirdMic/Service BirdMic/Boot
```

---

## Technical details

### Protocol

```
RTSP/1.0 over TCP, port 8554 (configurable)
Payload: RTP, Payload Type 96 (dynamic)
Codec:    L16 — PCM, 16 bit, Big Endian (RFC 3551)
Rate:     48000 Hz, 1 or 2 channels
Packet:   20 ms = 960 frames = 1920 bytes + 12-byte RTP header
RTCP:     Sender Report every 5 s
```

### SDP

```
v=0
o=- 0 0 IN IP4 192.168.1.42
s=BirdMic
i=Android microphone, uncompressed L16
c=IN IP4 0.0.0.0
t=0 0
a=tool:BirdMic
a=type:broadcast
a=range:npt=now-
m=audio 0 RTP/AVP 96
a=rtpmap:96 L16/48000/1
a=control:trackID=0
```

### Is this a WAV file?

**No.** The `audio.wav` path is only a name. The transfer is RTP with PCM payload.

Its content is exactly what an uncompressed WAV contains: a WAV is a 44-byte header plus raw PCM samples. The same samples travel over the wire, only without the header and in network byte order (Big Endian; WAV uses Little Endian). When ffmpeg writes a `.wav` from it, **not a single bit** is lost.

To process the raw bytes directly:

```bash
ffmpeg -f s16be -ar 48000 -ac 1 -i rohdaten.pcm ausgabe.wav
```

---

## Build it yourself

### With Android Studio

1. *Open* → choose this directory.
2. Install missing SDK components (API 34, Build-Tools 34.0.0).
3. *Build → Build Bundle(s)/APK(s) → Build APK(s)*.

### On the command line

Requires **JDK 17** (JDK 21+ does not work with Gradle 8.7) and the Android SDK.

```bash
./gradlew assembleRelease
```

Result: `app/build/outputs/apk/release/app-release.apk`

If the SDK is not at the default location, create `local.properties`:

```properties
sdk.dir=/path/to/android/sdk
```

### Versions

| | |
| --- | --- |
| Gradle | 8.7 |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 1.9.24 |
| compileSdk / targetSdk | 34 |
| minSdk | 26 (Android 8.0) |
| JVM target | 17 |

Dependencies are only `androidx.core:core-ktx` and `androidx.appcompat`. There is no third-party network, media, or DSP framework: RTSP, RTP, high-pass filtering, and FFT are handwritten. In total, there are about 4,000 lines of Kotlin in 21 files.

### Tests and static checking

```bash
./gradlew testReleaseUnitTest lintRelease
```

**60 unit tests** run on the JVM without device or emulator:

| Test class | Checks |
| --- | --- |
| `HighPassFilterTest` | Frequency response: 30 Hz attenuated, 250 Hz retained, 1 kHz unchanged, stereo channel separation |
| `FftTest` | Known sine tones reach the correct bin, silence stays empty, amplitude scales linearly |
| `RtpSenderTest` | RTP header per RFC 3550, sequence and timestamp progression, SSRC, overflow at 65535 |
| `DigestAuthTest` | Valid and invalid login, method binding, foreign nonce, randomness |
| `PacketQueueTest` | Order, discarding oldest packet, guaranteed non-blocking `offer` |
| `WebServerTest` | Real sockets: routes, 404/405, HEAD without body, Digest flow, excessive lengths, port actually released |
| `UpdateCheckTest` | What version checking must reject: nonsense instead of JSON, implausible numbers, file names with paths, clear-text HTTP, and an embedded address pointing to the private instance |
| `ConnectionLimitTest` | Records the 27 August 2026 incident: derived recipient count never becomes negative under 16 threads; oldest recipients give way instead of newest |

Lint must pass without errors or the build stops. It primarily catches API-level errors that the Kotlin compiler cannot see; a call existing only from Android 9 would otherwise crash on a `minSdk 26` device.

**What the tests do not cover:** everything requiring a device — `AudioRecord`, permissions, actual microphone selection, Wi-Fi behaviour, and Android power management. Those are checked manually with ffmpeg.

---

## Access protection, mDNS, log

### Access protection

Digest authentication is optional. Enter a username and password in both fields; leaving both blank disables it. Digest transmits only a hash, **but the audio remains unencrypted**. Use it on shared Wi-Fi; it is not a substitute for TLS or a trusted network.

### mDNS

When enabled, BirdMic advertises `_rtsp._tcp` on the local network. On the Pi, inspect it with:

```bash
avahi-browse -rt _rtsp._tcp
```

This reveals the current IP even when DHCP changes it. Do not rely on `birdmic.local` resolving; the phone decides that. A fixed router IP is more reliable.

### Log

The log stores the last 100 timestamped events across app restarts: starts, stops, connections, watchdog restarts, and warnings. Use **“Save log as file”** to create a diagnostic report containing device, settings, state, and the complete log.

---

## Web interface

Optional and **disabled by default**. When enabled it opens a second port (default 8080) and shows in a browser on any device in the same network:

```
http://192.168.1.42:8080/
```

- Status, address, level, and peak
- Live **spectrogram** that keeps running in the browser
- Recording source, active microphone, runtime, data volume, restarts, clipped samples, and battery
- Connected recipients, the last 30 log entries, and a link to `/log.txt`

It is useful when the phone is at a window or in a garden shed, or while positioning it and watching the spectrogram from a computer.

### What it deliberately cannot do

**Read only.** The server accepts only `GET` and `HEAD`; no route changes, starts, or stops anything. To change settings you must pick up the phone. This is intentional, not unfinished work.

| | |
| --- | --- |
| Routes | hard-wired, no file from storage — no path attacks |
| Connections | at most 4 at once, 5 s read timeout, no Keep-Alive |
| Page | one HTML file below 6 kB, compiled in, no loading — works without internet access |
| Access protection | same credentials as RTSP; the browser asks for them |
| Load | two requests per second per open browser; the spectrogram pauses itself in a background tab |

If the web server fails — for example because the port is occupied — audio continues unchanged; only a log line is added. The two share a data source, not a lifecycle.

### Security

Without access protection, **everyone on the same Wi-Fi** can see the status, log, and spectrogram. None is secret and nobody can listen through the page; it transfers no audio. Nevertheless it is disabled by default, and protection belongs on a shared network. Traffic is unencrypted HTTP.

---

## New versions

If **“Check for a new version when opening”** is enabled, the app queries only the project GitLab at most every six hours and only while it is open. It does not download or install anything. Installing replaces the app and stops a running stream.

The release job `veroeffentlichen` runs only once **all** tests and lint pass, compares the tag with `build.gradle.kts`, uploads the APK to the package registry, and creates the release. If tag and build disagree, it stops.

---

## Record several devices at once

The [`werkzeuge/`](werkzeuge/) directory contains two programs for recording and comparing multiple BirdMic streams **at the same time**. This is what the comparison test in [Devices under test](#devices-under-test) needs: three phones, the same position, one night, then an answer to which produces the better signal for BirdNET.

Both need **only Python** and no third-party library. No ffmpeg, no numpy, no installation.

### Record

```bash
python werkzeuge/aufnehmen.py --suchen
```

Searches `_rtsp._tcp` with mDNS and writes the found devices to `werkzeuge/geraete.txt`. Then use:

```bash
python werkzeuge/aufnehmen.py --dauer 300
```

All sessions are fully established before they start together, so recordings align without lead-in. Each device produces a `.wav` and `.json` in `werkzeuge/aufnahmen/<Zeitpunkt>/`.

The recording uses **TCP interleaved**. That is more robust on Wi-Fi and requires no open UDP ports.

### Why not simply run ffmpeg three times?

Because BirdMic provides two things that ffmpeg discards:

**The time reference.** Every five seconds the app sends an RTCP Sender Report with the phone’s wall-clock time. It can be used to calculate when the first sample of each file was created. Without it, three equally long files are only *approximately* simultaneous; with it they are genuinely comparable.

**The gaps.** If an RTP packet is lost, ffmpeg simply appends the subsequent data: the file becomes shorter than the interval it represents and everything after it shifts. `aufnehmen.py` detects the gap from the RTP timestamp, fills it with silence, and writes the count to the report. A recording with silent sections is a measurement result; one that secretly shrinks is a trap.

### Evaluate

```bash
python werkzeuge/auswerten.py
```

Takes the newest run and writes `bericht.md` in the same directory:

| Section | Contents |
| --- | --- |
| What arrived | Duration, lost packets, inserted silence, clipping, clock drift in ppm |
| Timeline | Device offset — **determined twice independently** |
| Level | Noise floor, median, peak, distance between noise and loudest event |
| Frequency distribution | Level per band, with 2–8 kHz highlighted separately |
| Loudest sections | Where events occur in the recording, with time |

The offset is deliberately determined **twice by separate methods**: once from phone clocks, once from cross-correlation of the measured loudness envelope. If both agree, the recordings are demonstrably synchronous. If not, the phone clocks differ — the report states that rather than silently basing every later number on a false assumption.

### Check without phones

```bash
python werkzeuge/attrappe.py
```

Starts three dummies on `127.0.0.1:8554` through `8556` that speak the same protocol as the app: different volumes, a 300 ms start offset, and one deliberately loses every 50th packet. This verifies that both programs do what they claim: the 300 ms must occur in **both** timeline columns, once from the clock and once from the measured level envelope.

### Through the runner instead of a computer

The GitLab runner is on the same network as the phones, so it can record at night without a computer running.

**Start:** In GitLab, under *Build → Pipelines → Run pipeline*, set `MITSCHNITT_DAUER` to the number of seconds. Without the variable, the job does not run, even on a normal push.

**Stop:** do not stop it — the job ends after `MITSCHNITT_DAUER` itself and only then uploads artefacts. Cancelling stops recording but **loses the artefacts**. For an earlier finish, use a shorter duration.

**Regularly:** create a schedule under *Build → Pipeline schedules* with the same variable. `30 4 * * *` is the obvious value for dawn.

Two things differ in a container and are already accounted for:

- **mDNS does not work.** Multicast does not pass a container network bridge. `_rtsp._tcp` discovery therefore finds nothing, regardless of how well the app advertises. `aufnehmen.py --netz` instead queries every address once on port 8554 and uses `DESCRIBE` to verify that BirdMic truly responds. An open port alone could also be a camera.
- **The container’s own network is the Docker network**, not Wi-Fi. An automatically determined network would therefore be wrong; `MITSCHNITT_NETZ` is explicit in the job.

Raw audio is **not** put in the artefact: an hour with three devices is around 1 GB while the limit is 100 MB. The report, per-device measurements, and excerpts around the loudest sections are retained.

Serving several recipients is not theory: while recording runs, BirdNET-Pi stays connected to the same stream. Every session gets its own SSRC.

### Test the app against real devices

The tests under `app/src/test/` test calculations — Digest, FFT, high-pass, PacketQueue, RtpSender — without device or network. That is their limit. None can know whether the finished phone really speaks RTSP on Wi-Fi.

```bash
python werkzeuge/pruefen.py --netz 192.168.1.0/24
```

It finds running devices and verifies every promise in this README: `OPTIONS` lists all methods; `DESCRIBE` supplies L16 with rate and channels; `SETUP` answers TCP interleaved with session and timeout; `seq` and `rtptime` from `PLAY` match the first packet that actually arrives; 100 packets have no sequence or timestamp jump; an RTCP Sender Report arrives within six seconds; the phone clock is correct; audio exists; two recipients are served concurrently with an SSRC each; `GET_PARAMETER` keeps the session alive; `TEARDOWN` ends it cleanly; and the web interface reports a healthy state.

With `--bericht ergebnis.xml`, it writes a JUnit file. In the pipeline it appears under *Tests*: one suite per device and one case per promise.

The runner uses `GERAETETEST`, manually or from a schedule. This job is deliberately blocked until the variable is present: a switched-off phone must not create a red build without a code fault.

This is not ceremonial: in the first run against two test devices, the checker reported *“Device clock differs by +2.9 s”* on one and −1.9 s on the other. Together, 4.8 seconds — the same number independently calculated by the recording report from Sender Reports. Two different methods, the same result: the clocks really differ.

### Limitations that remain

- The level comparison has **100 ms** resolution. That is sufficient to verify clocks, not to determine direction from time differences.
- With a **periodic** sound — pump, traffic, hum — the loudness envelope fits several positions equally well. The report writes `mehrdeutig` and claims nothing.
- Noise floor is comparable only between devices with **the same gain**. The *noise/peak distance* column is independent of that.
- A comparison says something about *this* recording. A device that performed well through a quiet night may fail in rain.

---

## Known limitations

- **Only a single device has so far been proven in continuous operation.** Version 2.8 has run continuously on a Nokia 2.3 (Android 11) since 04 September 2026; 60 unit tests cover device-independent parts and Lint passes without errors. This says nothing about other devices or Android versions; see the following two points.
- **Android 8 and 9 are untested.** `minSdk` is 26 and the branches for these versions exist (Lint enforces them), but the app has run only on 10 and 11. Feedback from anyone with 8 or 9 is welcome. On 8.x, “Active microphone” is empty by design because `getActiveMicrophones()` starts only at 9.
- **Android 12 through 14 are also untested.** Autostart from 14 is handled and reports itself rather than failing silently, but the app has not yet run on a device of that generation.
- **No session state machine.** Unusual method sequences (`PLAY` before `SETUP`, `PAUSE` without `PLAY`) are handled but not systematically checked through an explicit state.
- **IPv4 only.** The app determines and reports IPv4 addresses only.
- **Autostart is blocked from Android 14** (see above) — a system restriction.
- **Audio is unencrypted**, even with access protection.
- **The spectrogram is a sample**, not a continuous display: about eight times per second it retrieves the last 1,024 samples. That is enough to check placement, interference, and filter effect; it is not a measurement tool.
- **UDP timeout relies on keepalives.** Clients that send none would be disconnected after expiry. It is therefore off by default, and ffmpeg — which sends keepalives and uses TCP anyway — is not affected.
- **One track only:** audio only, no video, deliberately.
- **Mono is the sensible choice.** A phone’s built-in microphone is unsuitable for directionality and BirdNET does not evaluate stereo information.
- **`WIFI_MODE_FULL_HIGH_PERF` is deprecated from Android 10.** It behaves as intended on Android 8–11; on newer versions it has no effect but is not harmful. The build warns accordingly.
- **Wi-Fi interruptions** break the connection. ffmpeg on the Pi reconnects automatically; recordings gain a gap.
- **IPv6 is not offered** — the app reports IPv4 addresses only.
- **Nothing is automatically tested on the device.** The 60 JVM unit tests cover Digest, packet queue, filter, FFT, web interface, and connection limit; there are no instrumentation tests on a physical phone. Everything visible only on a device is checked manually with ffmpeg.

---

## Code structure

| File | Task |
| --- | --- |
| [`AudioEngine.kt`](app/src/main/java/net/birdmic/rtsp/AudioEngine.kt) | `AudioRecord` thread, watchdog, 20-ms blocks, Little→Big Endian, level, effect shutdown |
| [`AudioDevices.kt`](app/src/main/java/net/birdmic/rtsp/AudioDevices.kt) | Input-device enumeration and stable keys, source descriptions, position interpretation |
| [`HighPassFilter.kt`](app/src/main/java/net/birdmic/rtsp/HighPassFilter.kt) | Two cascaded Butterworth biquads, 24 dB/octave |
| [`Fft.kt`](app/src/main/java/net/birdmic/rtsp/Fft.kt) | Radix-2 FFT with Hann window for the display |
| [`SpectrogramView.kt`](app/src/main/java/net/birdmic/rtsp/SpectrogramView.kt) | Rolling spectrogram on a ring-buffer bitmap |
| [`DigestAuth.kt`](app/src/main/java/net/birdmic/rtsp/DigestAuth.kt) | RTSP Digest authentication per RFC 2617 |
| [`MdnsAdvertiser.kt`](app/src/main/java/net/birdmic/rtsp/MdnsAdvertiser.kt) | Service advertisement through `NsdManager` |
| [`BatteryWatch.kt`](app/src/main/java/net/birdmic/rtsp/BatteryWatch.kt) | Temperature, charge state, warning threshold with hysteresis |
| [`EventLog.kt`](app/src/main/java/net/birdmic/rtsp/EventLog.kt) | Timestamped ring buffer surviving app restarts |
| [`PacketQueue.kt`](app/src/main/java/net/birdmic/rtsp/PacketQueue.kt) | Ring buffer per client, decoupling recording and networking |
| [`QuickTileService.kt`](app/src/main/java/net/birdmic/rtsp/QuickTileService.kt) | Quick Settings tile |
| [`RtpSender.kt`](app/src/main/java/net/birdmic/rtsp/RtpSender.kt) | RTP header, RTCP Sender Reports, UDP and TCP-interleaved transport |
| [`RtspServer.kt`](app/src/main/java/net/birdmic/rtsp/RtspServer.kt) | RTSP methods, SDP, one session per client |
| [`RtspService.kt`](app/src/main/java/net/birdmic/rtsp/RtspService.kt) | Foreground service, wake and Wi-Fi locks, notification, UI state |
| [`MainActivity.kt`](app/src/main/java/net/birdmic/rtsp/MainActivity.kt) | Interface, permissions, settings |
| [`Prefs.kt`](app/src/main/java/net/birdmic/rtsp/Prefs.kt) | Settings, IPv4 detection |
| [`BootReceiver.kt`](app/src/main/java/net/birdmic/rtsp/BootReceiver.kt) | Autostart after reboot |

### Permissions and why

| Permission | Purpose |
| --- | --- |
| `RECORD_AUDIO` | The microphone |
| `INTERNET` | Open server socket |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | Wi-Fi lock |
| `ACCESS_NETWORK_STATE` | Determine own IP |
| `WAKE_LOCK` | Keep CPU awake |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | Background recording |
| `RECEIVE_BOOT_COMPLETED` | Autostart |
| `POST_NOTIFICATIONS` | Status notification from Android 13 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | The one-tap dialog for continuous operation |

The app sends **no data externally**, has no analytics or advertising SDKs. Its only network traffic is the RTSP stream to clients that connect themselves.

---

## Ideas for later

### Opus or AAC mode for weak connections

For a stream over something slower than Wi-Fi. Deliberately not built because it conflicts with the purpose: compression harms precisely the frequency range in which BirdNET distinguishes species. An implementation should use AAC-LC; the `MediaCodec` Opus encoder exists only from Android 10 and is missing on the old devices for which the app is intended.

### Continuous spectrogram

It currently samples eight windows per second. A continuous display would require the engine to collect FFT windows itself instead of the interface taking the most recent block.

### Record to SD card when the network is unavailable

This would close gaps during Wi-Fi failures. It needs storage management, rotation, and a delivery strategy — substantially more work than it first appears.

### Automatic gain calibration

Measure the environment for 30 seconds once and propose a gain. Explicitly *not* a running controller: that would again be AGC, which is deliberately disabled here.

### Health endpoint for monitoring from the Pi

A small HTTP `/status` endpoint with runtime, restarts, recipient count, clipping, and battery temperature. The Pi could then notice a microphone that has supplied nothing for an hour instead of this first appearing in evaluations. It would be useful for an autonomous outdoor station, but costs a second server in the process and an additional attack surface.

### mDNS TXT records

Include `path`, `codec`, `rate`, and `channels` as TXT records so a client can configure the stream fully automatically.

### Level in dBFS instead of percent

“Peak −8.6 dBFS / RMS −39.2 dBFS” is much more meaningful when setting up recordings than “Peak 37%”. It could also yield a noise-floor indicator.

### RTSP integration tests

Done — see [Test the app against real devices](#test-the-app-against-real-devices).

---

## Licence

MIT — see [LICENSE](LICENSE).
