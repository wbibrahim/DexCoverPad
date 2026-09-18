# Dex Touchpad (Shizuku Edition)

Use the Samsung Flip7 cover display as a touchpad for Samsung DeX — no ADB required, uses Shizuku instead.

## Requirements

- Samsung Galaxy Z Flip7 (or similar with a cover display)
- [Shizuku](https://shizuku.rikka.app/) installed and running
- Samsung DeX

## Setup

1. Install [Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) from the Play Store
2. Start Shizuku (follow the Shizuku app instructions — wireless ADB is only needed once for Shizuku itself, not this app)
3. Install this APK
4. Open the app and grant Shizuku permission when prompted
5. The cover display becomes a touchpad for DeX

## How to Build

### GitHub Actions (recommended)

Push this repo to GitHub. The workflow in `.github/workflows/build.yml` automatically builds a signed debug APK on every push. Download the APK from the Actions tab → Artifacts.

### Local Build

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

## How It Works

- Shizuku grants shell-level privileges without permanent ADB
- The app launches a `UserService` via Shizuku running with elevated UID
- The `UserService` loads `libdextouchpad.so` through JNI and creates a virtual UHID mouse device
- Touch input on the cover display is translated to mouse movements sent to the virtual device
- DeX sees the virtual mouse as a real cursor controller

## Gestures

| Gesture | Action |
|---|---|
| Single finger drag | Move cursor |
| Two finger scroll | Scroll |
| Double tap | Left click |
| Long press | Right click |
| Left Click button | Left click |
| Right Click button | Right click |
