# ⚡ AirBridge — Seamless Phone-to-PC Companion

> **Low-latency Virtual Microphone, Instant Image Vault & GPU-Accelerated AI Speech-to-Text for Linux & Android.**

AirBridge connects your Android phone and Linux computer into a unified workspace. Speak into your phone to talk in Discord or Zoom with crystal-clear 48 kHz low-latency audio, beam photos with 1 tap straight to your desktop clipboard, and dictate text that transcribes with local RTX GPU Whisper and automatically types into your active Linux window.

---

## 📥 Direct APK Download & Install

| Direct 1-Tap Download | Scan QR Code with Phone Camera to Install |
| :---: | :---: |
| [![Download APK](https://img.shields.io/badge/Download-AirBridge.apk-06B6D4?style=for-the-badge&logo=android&logoColor=white)](https://github.com/pharmacophobia/AirBridge/raw/main/AirBridge.apk)<br><br>👉 **[Click here to download AirBridge.apk (15 MB)](https://github.com/pharmacophobia/AirBridge/raw/main/AirBridge.apk)**<br><br>📦 Alternate: [Official GitHub Releases](https://github.com/pharmacophobia/AirBridge/releases) | <img src="https://api.qrserver.com/v1/create-qr-code/?size=180x180&data=https://github.com/pharmacophobia/AirBridge/raw/main/AirBridge.apk" width="180" height="180" alt="Scan to install APK" /><br>*(Point your phone camera to download)* |

---

## ✨ Core Features

### 🎙️ 1. Phone as High-Fidelity PC Virtual Microphone
- **PulseAudio & PipeWire Native Integration**: Feeds raw 48,000 Hz 16-bit PCM audio directly into `AntigravityVirtualMic` / `AirBridgeMic`.
- **Works in Any Linux App**: Immediately selectable in Discord, Zoom, OBS, Google Meet, Teams, Audacity, and web browsers.
- **Ultra-Low Latency (<15ms)**: UDP datagram streaming avoids TCP head-of-line blocking and buffer stalls.
- **Android Background Service**: Robust `ForegroundService` with notification controls keeps audio streaming continuously even when multitasking or with the screen off.
- **Hardware Controls**: Live pulsating VU level meter, digital gain booster (0.5x – 3.0x), and instant hardware mute.

### 🖼️ 2. Seamless Image Transfer & Instant Clipboard Sync
- **1-Tap Share Sheet Target**: Open Google Photos, Camera, or any browser, tap **Share**, and choose **AirBridge** to beam images to your PC.
- **In-App Gallery & Camera Capture**: Multi-select images from your photo library or snap quick photos to send immediately.
- **Auto-Paste Desktop Clipboard**: Incoming images are automatically copied into your Linux system clipboard (`QClipboard.setImage()`) — press `Ctrl+V` to paste directly into Discord, Slack, GIMP, or Telegram.
- **Persistent Vault**: All images are saved to `~/Pictures/AirBridge/` with quick buttons to open in your default viewer or file manager.

### 🤖 3. AI Speech-to-Text & Active Window Auto-Typing
- **Local GPU-Accelerated Whisper STT**: Powered by local NVIDIA CUDA faster-whisper on port 8200 (`large-v3-turbo` / `medium.en`).
- **Dictate from Phone to PC**: Speak into the phone voice recorder; the audio is transcribed in seconds and beamed to your PC.
- **⌨️ Live Auto-Type into Active Window**: Automatically uses `xdotool` to simulate real-time typing into whichever code editor, terminal, or document is focused on your PC.
- **Clipboard Sync**: Instantly copies transcribed speech to both Android and Linux clipboards.

### 📶 4. Zero-Config Pairing & Dual Connection Modes
- **LAN Auto-Discovery**: Automatic UDP broadcast beacon (`AIRBRIDGE_DISCOVER` on port 8767) finds your PC on local Wi-Fi in under 1 second.
- **Zero-Latency USB Mode (ADB Reverse)**: 1-click ADB reverse tethering (`adb reverse tcp:8765 tcp:8765`) lets you stream over USB cable with 0ms latency even without Wi-Fi.

---

## 🚀 Quick Start Guide

### 1. Launch the Linux Desktop Companion
```bash
cd /home/k/AirBridge
./run.sh
```
*(Or launch **AirBridge** directly from your Linux Application Launcher / Start Menu).*

### 2. Install the Android App on Your Phone
1. Scan the QR code above or download `AirBridge.apk` directly to your phone.
2. Tap the downloaded APK to install.
3. Open **AirBridge** on your phone.
4. Tap **Auto-Scan** (or enter the PC IP displayed in the desktop window).

### 3. Using the Virtual Mic in Discord / Zoom / Chrome
1. In the AirBridge Android app, tap the large circular **Microphone** button to turn it green.
2. In Discord / Zoom / Chrome audio settings, set your **Input Device** to:
   - **Antigravity_Virtual_Microphone** (or **AirBridge_Microphone**).
3. Speak normally into your phone!

---

## 🛠️ Architecture & Project Structure

```
AirBridge/
├── AirBridge.apk               # Official precompiled Android release APK
├── run.sh                      # 1-click Linux desktop launcher
├── install_desktop.sh          # Installs .desktop launcher & system icon
├── README.md                   # Documentation, direct APK download & QR code
├── .gitignore                  # Git rules whitelisting !AirBridge.apk
├── desktop/                    # Linux Desktop Companion (PyQt6 / Python 3)
│   ├── main.py                 # Cyber-Dark GUI, tabs, live VU meters, clipboard
│   ├── audio_bridge.py         # PulseAudio/PipeWire virtual mic pipeline (pacat)
│   ├── server.py               # HTTP API server & low-latency UDP audio receiver
│   ├── whisper_client.py       # Integration with local Whisper STT (RTX 2070 server)
│   ├── pairing.py              # UDP broadcast discovery & LAN IP detection
│   └── assets/                 # Icons & branding assets
└── android/                    # Android Companion App (Kotlin / Jetpack Compose)
    ├── app/
    │   ├── src/main/
    │   │   ├── AndroidManifest.xml   # Foreground mic service & share-sheet intent filters
    │   │   ├── res/                  # Material 3 colors, themes, strings
    │   │   └── java/com/airbridge/app/
    │   │       ├── MainActivity.kt        # Compose shell, tabs, permission flows
    │   │       ├── ui/                    # MicScreen, ImageTransferScreen, AiTranscriptionScreen
    │   │       ├── service/               # MicStreamService (AudioRecord foreground streaming)
    │   │       └── network/               # ApiClient, DiscoveryClient, UDP audio streamer
    └── gradlew                 # Gradle build tool
```

---

## ⚙️ Requirements

- **Linux PC**:
  - Ubuntu / Debian / Arch / Fedora with PipeWire or PulseAudio
  - Python 3 with `PyQt6`, `Pillow`, `numpy` (pre-installed)
  - `pacat` (standard in `pulseaudio-utils` / `pipewire-pulse`)
  - Optional: `xdotool` (for auto-typing dictation into active windows)
  - Optional: Local faster-whisper STT on `http://127.0.0.1:8200`
- **Android Phone**:
  - Android 8.0 (API 26) or higher
  - Wi-Fi or USB Debugging connection to PC

---

## 📜 License

MIT License — Feel free to customize, modify, and build upon AirBridge!
