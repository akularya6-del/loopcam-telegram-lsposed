<div align="center">

# loopcam-telegram-lsposed

**Android Xposed / LSPosed module with companion dashboard to inject custom looping camera feeds into Telegram video calls.**

![Android](https://img.shields.io/badge/Platform-Android_8.0+-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![LSPosed](https://img.shields.io/badge/Framework-LSPosed_/_Zygisk-red?style=flat-square)
![Target](https://img.shields.io/badge/Target-Telegram-24A1DE?style=flat-square&logo=telegram&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)

</div>

---

## 📌 Overview

**LoopCam** is an Android LSPosed module that intercepts Telegram's runtime Camera2 API pipeline, allowing you to stream a selected video file as your live camera feed during video calls. It consists of:
1. **Companion Dashboard (Activity)**: A UI to select files, configure playback parameters, and toggle feed injection.
2. **LSPosed Runtime Hook**: Hooks into Telegram's process (`org.telegram.messenger`) to redirect camera stream requests to an internal video surface.

---

## ⚡ Requirements

- **Rooted Android Device** (Android 8.0+ / API 26+)
- **LSPosed Framework** (Zygisk flavor recommended)
- **Telegram** (`org.telegram.messenger`) installed

---

## 🏗️ Architecture & IPC Bridge

```
[Companion App]
      │
      ├── Copies video to shared cache:
      │   /sdcard/Android/data/com.loopcam/cache/loopcam_cache/video.mp4
      │
      └── Sets injection state in SharedPreferences
            │
            ▼ (XSharedPreferences IPC)
[LSPosed Runtime Hook]
      │
      └── Injected inside Telegram Process
            │
            ▼ Hooks Camera2 / CameraCaptureSession
[Telegram Video Stream] ◄── Custom video feed loop
```

---

## 🚀 Building & Installation

### Build from Source

```bash
# Clone the repository
git clone https://github.com/akularya6-del/loopcam-telegram-lsposed.git
cd loopcam-telegram-lsposed

# Build release APK using Gradle
./gradlew assembleRelease
```

### Installation

1. Install the built APK:
   ```bash
   adb install app/build/outputs/apk/release/app-release.apk
   ```
2. Open **LSPosed Manager** → **Modules** tab.
3. Enable **LoopCam** and set the target scope to **Telegram**.
4. Force-stop Telegram (`am force-stop org.telegram.messenger`) so the runtime hooks bind on next startup.

---

## 📄 License

This repository is distributed under the [MIT License](LICENSE).
