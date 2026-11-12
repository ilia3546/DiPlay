# DiPlay

[English](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md)

> **This is a fork of [upstream DiPlay](https://github.com/shihabal3amri/DiPlay) with Geely Monjaro support.** It is not affiliated with Geely or with the upstream author. Report Monjaro issues in this fork, not upstream. The Monjaro part comes first; the rest of this README, below the line, is upstream's.

## DiPlay on the Geely Monjaro

DiPlay runs next to the car's own CarPlay as a separate app and brings wireless CarPlay with the cluster, the head-up display and the steering wheel wired in. Everything Monjaro-specific lives in one settings card, **Settings → Advanced → Geely Monjaro A3 Support**, is **off by default** and shows only on this head unit. With every option off, DiPlay behaves like upstream.

### The car it is built and tested on

| | |
|---|---|
| **Car** | Geely Monjaro (KX11), facelifted model with the Android 11 head unit |
| **Head unit** | Desay SV, Qualcomm SA8155 (`msmnile_gvmq`), Android 11 (API 30) |
| **Android identifiers** | product `geely_fx11`, model `KX11_A3_L1` |
| **Firmware** | `KX11A3L1.INT.IC3085.41.SOC.E172.202603031906.R` (E172) |
| **Cluster and HUD** | drawn by the QNX side of the head unit; the HUD is an 800×480 display |
| **iPhone** | iOS 27 |

The settings card appears when the product is `geely_fx11` and the model starts with `KX11_A3`. Other Monjaro firmware, the pre-facelift Monjaro (Android 9) and other Geely or Lynk & Co cars are untested.

### What you need

| | Required for | |
|---|---|---|
| 🔌 **ADB to the head unit** | everyone | The APK is installed with `adb install -r DiPlay.apk`; a few one-time steps below also run over ADB. Keep a trusted computer for it. |
| 📶 **iPhone paired over Bluetooth, car hotspot on** | everyone | Wireless CarPlay runs over the car's own hotspot (5 GHz) or [Existing Wi-Fi / Same LAN](docs/EXISTING_WIFI.md). See [Install and connect](docs/INSTALL.md). Wired USB CarPlay does not work on this car: the Android side of the head unit sees no USB devices. |
| 🪟 **"Draw over other apps"** | custom dashboard, HUD | DiPlay asks for it when you turn either on. Over ADB: `adb shell appops set com.shihab.diplay SYSTEM_ALERT_WINDOW allow`. |
| 🔓 **Root (Magisk)** | the options marked *root* below | Allow DiPlay in Magisk. Nothing else needs root. |
| 🔑 **QNX root password** | trip data and the stock bottom line on the custom dashboard | Optional. Stored on the head unit only, never in a settings backup. |
| 🚫 **Turn off overlapping apps** | custom dashboard, HUD | Turn off the GMC custom dashboard before DiPlay's, and the output of the HUD Renderer app (`org.monjaro.hudrenderer`) before DiPlay's HUD, or both draw at once. |

The car's own CarPlay app stays installed and untouched; run one CarPlay at a time. To remove DiPlay: `adb uninstall com.shihab.diplay` and forget the car in **Settings → General → CarPlay** on the iPhone.

### What works

Out of the box, with no option on:

- Wireless CarPlay over the car hotspot or Existing Wi-Fi, full-screen on the 1920×720 centre display, with touch.
- Music, calls and Siri through the car's speakers and cabin microphones.
- Steering-wheel keys: play/pause, next and previous go to CarPlay; a long press of the microphone key opens Siri (see [steering-wheel keys](docs/COMPATIBILITY.md#geely--lynk--co-steering-wheel-keys) if the car's assistant answers instead).
- With **Report location to iPhone** on, the car's wheel speed and gear go to the iPhone for positioning in tunnels.

In the **Geely Monjaro A3 Support** card, grouped as on screen:

**Connection**

| Option | What it does | Needs |
|---|---|---|
| Exact car hotspot details | Reads the hotspot channel that Android hides from the firmware's hotspot configuration and tells the iPhone the hotspot's BSSID. | root |
| Turn the car hotspot on with root | Turns the saved car hotspot on when DiPlay opens and before a wireless connection. | root |
| Disconnect iPhone Bluetooth during wireless CarPlay | Once CarPlay runs, drops the iPhone's Bluetooth calls, music and media keys from the head unit, as the car's own CarPlay does. | root |
| Let CarPlay audio through the car firewall | Lets the iPhone's audio through the firewall that otherwise cuts CarPlay sound after a few seconds on the hotspot. | root |
| Request lost audio packets again | Asks the iPhone to resend music packets lost on Wi-Fi and holds playback briefly to put them in place. | |

**Sound and steering wheel**

| Option | What it does | Needs |
|---|---|---|
| Microphone input | Picks the recorder for calls and Siri: Auto, the voice assistant bus or all cabin microphones mixed, so rear passengers are heard too. | |
| Pause CarPlay when the car plays its own audio | Pauses the iPhone when the radio or another head-unit app starts playing, and leaves the wheel keys to that source. | |
| Hold next or previous to fast-forward or rewind | Holding a skip key moves the track 10 s every half second; a short press still skips. | |

**Car's own dashboard**

| Option | What it does | Needs |
|---|---|---|
| CarPlay music on the dashboard and media widgets | Track, artist, album and cover in the stock cluster's music widget and the driver and passenger widgets; their buttons control CarPlay. | root only if the head unit refuses |
| CarPlay calls on the dashboard and HUD | CarPlay calls in the stock call widget, on the HUD and on the custom dashboard. While it rings, previous declines and next answers; during a call, previous mutes and next hangs up. | root only if the head unit refuses |

**Custom dashboard**

| Option | What it does | Needs |
|---|---|---|
| Custom dashboard with CarPlay map | Replaces the stock cluster's side widgets with DiPlay's own dashboard: the CarPlay map in the middle, clock, outside temperature, trip and music around it. The firmware keeps drawing the speed, warning lights and bottom line. | overlay, GMC dashboard off |
| Map width | Narrow, medium or wide map; the iPhone sends the new size after you reconnect. | |
| Next maneuver / Arrival over the map | The next turn with its distance and street, and the arrival time with the time and distance left, over the map's corners. | |
| Dashboard background and dimming | Thirteen backgrounds or your own photo, dimmed by 0 to 60 %. | |
| Trip data from the QNX side | Reads the trip computer from the QNX side of the head unit. | QNX password |
| Keep the stock bottom line | Brings back the stock RPM, odometer, fuel, coolant, ECO and gear line if something clears it. | QNX password |
| Hold the dashboard view key to show the car's own dashboard | A 1.5 s hold of the wheel's dashboard view key hides the custom dashboard and gives the cluster back to the car until the next hold; a short press still switches the car's own view. | |

**Head-up display**

| Option | What it does | Needs |
|---|---|---|
| Navigation on the HUD | The next maneuver with roundabout exits, its distance and street, then the lanes and the time and distance left, drawn on the HUD while CarPlay guides you. | overlay, HUD Renderer output off |
| HUD line position and scale | Where the line sits and how large it is. | |
| Lanes / Time and distance left on the HUD | Show or hide either line. | |

All options are experimental. Each one says in the app whether it applies at once or at the next connection. Unrelated upstream options that help on this car: **Calls at the call's own rate** (WhatsApp calls that kept restarting run at 32 kHz with it on).

### Known limits

- Wired USB CarPlay: not available, see above.
- CarPlay Ultra is not supported: the iPhone's instrument cluster stream carries only a map.
- Firmware updates can change any of the above; after one, check the options you use.

---

**CarPlay for compatible BYD Android head units.** Wired and wireless, with the familiar DiAuto interface. Independent app: `com.shihab.diplay`.

> **BYD support scope:** These projects focus on BYD cars. They may work on other brands, but other brands are unsupported and there are no plans to add support or fix brand-specific incompatibilities.

[Download & website](https://shihabal3amri.github.io/DiPlay/) · [Release](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.22) · [Report a problem](https://github.com/shihabal3amri/DiPlay/issues/new/choose)

![DiPlay home](site/assets/home.png)

## 0.2.22 — public preview

Install on the **car**, not the iPhone. No jailbreak, dongle, Mac, account or authentication server is required for use. Core CarPlay does not require ADB; optional dashboard, battery, wheel-speed and parked-video features do. Your head unit must permit APK installation. The APK supports Android 7.0+ (API 24); on Android 7.0–8.1, wired CarPlay is confirmed on one car and wireless is not yet confirmed on a vehicle. Wireless supports Wi-Fi Direct, the car’s existing hotspot or Existing Wi-Fi / Same LAN. Android 7.0–9 Wi-Fi Direct uses a firmware-dependent legacy path with generated group credentials and unverified requested frequency; see [Android 9 Wi-Fi Direct](docs/ANDROID9_WIFI_DIRECT.md). Android 10+ verifies its negotiated group frequency.

- Wired USB and wireless CarPlay with local authentication.
- BYD HUD navigation with arrows, distance and street names on verified firmware.
- Car hotspot support, improved audio buffering and saved receive diagnostics.
- Automatic address discovery, fixed-channel Wi-Fi fallbacks and successful-configuration memory.
- Icon/text size, resolution and frame rate; applying a display change reconnects CarPlay.
- Local diagnostic export. Reports are sent only if you choose to share them.
- Separate installation alongside DiAuto. Run one projection app at a time.

This is **not an Apple-certified product**. The APK bundles an experimental accessory identity recovered from public Carlinkit firmware, not a newly provisioned MFi identity for DiPlay. A bundled private key is extractable. Acceptance after future iOS updates, reliability across head units and suitability of that identity for general distribution are unresolved. This release invites community testing; it is not a guarantee of universal compatibility.

Earlier releases were tested on the development DiLink5.1 car: live windshield guidance and street names work, Car hotspot now starts CarPlay, and Wi-Fi Direct performance is substantially improved. Audio underrun recovery is improved in 0.2.15; remaining cutouts need current diagnostic reports. The 0.2.16 software Opus microphone fallback was accepted on a BOS Mini A1 head unit (Android 9) with an iPhone 12 on iOS 27. The floating-map test build was installed on the development DiLink 5.1 car; feedback led to the pinch corrections in 0.2.9. Earlier wheel-speed and video contributions were tested on a BYD Tang with DiLink 5.0 and an iPhone 15 Pro on iOS 27; wheel-speed dead reckoning in tunnels remains unverified. Broader head-unit and iOS compatibility is not guaranteed. The HUD firmware scope and cleanup limits are documented in [BYD navigation](docs/BYD_NAVIGATION.md).

## What’s new in 0.2.22

- Wireless CarPlay over the car hotspot connects again on BYD DiLink 3.0 and 4.0 head units where 0.2.21 stayed on "Opening CarPlay"; the **Hotspot interface (optional)** workaround can be cleared.
- Music plays at the iPhone's speed over Wi-Fi again, from the first second of every song, without the 0.2.21 speed-up and stutter.
- Over USB, WeChat voice messages, Voice Memos and dictation record your voice instead of silence.
- Wired CarPlay connects on the first attempt after a replug, and the CarPlay screen and Home say why a connection failed and what to change.
- DiPlay is available in Korean and Turkish.
- New options, off by default: keep CarPlay in the launcher card, navigation prompts that ask for audio focus, calls at the call's own rate, a full-screen DiLink 3.0 dashboard map and the dashboard map on the Denza N9.

See [0.2.22 release notes](docs/RELEASE-NOTES-0.2.22.md) and [validation](docs/VALIDATION.md) for contribution links and remaining physical tests. General stutter, calls/Siri, decoder and model-specific reports still need current-device evidence. [0.2.21 notes](docs/RELEASE-NOTES-0.2.21.md) remain available as historical guidance.

If a problem remains, reproduce it on **0.2.22**, then use **Settings → Diagnostics → Save diagnostic report**. Android 10+ normally saves to **Downloads/DiPlay**; Android 7.0–9 asks for storage access and saves there too. If unavailable, use **View report** or **Share** from the confirmation, which identifies external/private fallback storage. Review the `.txt` and attach it as a file to a matching [existing issue](https://github.com/shihabal3amri/DiPlay/issues), or [create one](https://github.com/shihabal3amri/DiPlay/issues/new/choose). Include vehicle/head-unit model, exact firmware and Android/DiLink, phone/iOS, connection backend, relevant settings, steps and failure time. Reports are shared only when you choose; never post your hotspot password.

## Documentation

[Existing Wi-Fi / Same LAN](docs/EXISTING_WIFI.md) keeps the iPhone and head unit
on an external router. See the guide for setup, build requirements and the
BYD DiLink 4.0 / Android 10 clean-install validation result.

- [Install and connect](docs/INSTALL.md)
- [Compatibility and troubleshooting](docs/COMPATIBILITY.md)
- [Smooth wireless CarPlay](docs/SMOOTH_WIRELESS.md)
- [Privacy and diagnostic reports](docs/PRIVACY.md)
- [Build from source](docs/BUILD.md) — select `mobile` for the main DiPlay app; `maphost` is a map sample.
- [Validation](docs/VALIDATION.md)
- [Release notes](CHANGELOG.md)
- [Credits and licenses](docs/THIRD_PARTY_NOTICES.md)

The app and the website include English, Arabic, Russian, Ukrainian, Spanish, Simplified Chinese, Traditional Chinese (Taiwan), Japanese, Italian, Korean and Turkish. Traditional Chinese uses Taiwan wording; the app also recognizes Hong Kong/Macao and explicit Hant selections without claiming separate regional translations. Choose the app language in Settings; on Android 13+, it stays synchronized with Android’s per-app language setting. See the [Traditional Chinese guide](README.zh-TW.md) for installation, settings, troubleshooting, privacy and build instructions.

## Source and credits

Based on [xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0. The home/settings UI and website adapt [DiAuto](https://github.com/shihabal3amri/DiAuto), AGPL-3.0; that license is included in `docs/licenses`. Preserve those notices when distributing modifications. CarPlay and its icon belong to Apple Inc.; no Apple or BYD affiliation or endorsement is implied.

This repository starts with a clean public source snapshot. Local research, tester reports and release-signing secrets are excluded. The complete source corresponding to the APK is provided with every release; experimental runtime identity assets are described separately in the build instructions and notices.

## Local release packaging

The release APK intentionally contains the experimental accessory identity. The Git repository and source archive exclude all accessory and Android signing keys; tests generate synthetic identities at runtime. Source/CI builds omit runtime identity assets by default. Local release builds explicitly select an external asset directory. Publishing the APK makes its bundled identity extractable; building locally does not preserve that identity's confidentiality.
