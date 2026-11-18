# MirrorOnTheWall

Mirrors an Android phone's screen to an Android TV over local Wi-Fi with WebRTC. No cloud services are involved.

**Status: Phase 0.** This is the project skeleton. Nothing mirrors yet. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the design and the phase plan.

## Modules
- `:sender`: phone app (Compose, minSdk 29)
- `:receiver`: Android TV app (Leanback launcher, minSdk 26)
- `:common`: shared protocol and constants (Kotlin/JVM)

## Build prerequisites
- JDK 17 or newer
- Android SDK with `platforms;android-37` and `build-tools;36.0.0`. AGP installs missing packages automatically once the SDK licenses are accepted.
- Network access to `dl.google.com` (Google Maven), `repo1.maven.org` and `services.gradle.org`

```
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew assembleDebug
./gradlew :common:test
```

Full build, install (including `adb connect <TV_IP>:5555`) and measurement instructions are written in Phase 6.
