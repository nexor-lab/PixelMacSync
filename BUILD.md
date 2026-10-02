# BUILD.md — building PixelMacSync

## macOS (PixelSync.app) — macOS 12.7, Intel x86_64

Requirements: **Command Line Tools** (full Xcode not required).

```sh
xcode-select --install        # if not present
cd MacOS
./build_macos.sh              # -> MacOS/build/PixelSync.app  (x86_64)
```

Options:

```sh
ARCH=arm64     ./build_macos.sh   # Apple Silicon
ARCH=universal ./build_macos.sh   # fat binary
```

The script:
1. compiles `BLEManager.swift`, `ContentView.swift`, `PixelSyncApp.swift` with
   `swiftc -target x86_64-apple-macos12.0` (CLT SDK);
2. writes a complete `Info.plist` (menu-bar agent, Bluetooth usage string);
3. generates `AppIcon.icns` from the original Icon Composer PNGs;
4. ad-hoc code-signs the bundle.

Verify:

```sh
file MacOS/build/PixelSync.app/Contents/MacOS/PixelSync
# -> Mach-O 64-bit executable x86_64
```

## Android (MacSync APK)

Requirements:
- **JDK 17+** (AGP 9 / Gradle 9). The build was prepared with Temurin 21.
- **Android SDK** with `platforms;android-36`, `build-tools;36.0.0`,
  `platform-tools`.
- The Gradle wrapper (`Android/gradlew`) downloads Gradle 9.4.1 itself.

```sh
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME="$HOME/Library/Android/sdk"     # or your SDK root
export PATH="$ANDROID_HOME/platform-tools:$PATH"

cd Android
./gradlew :app:assembleRelease     # -> app/build/outputs/apk/release/app-release.apk
# or, for a quick debug build:
./gradlew :app:assembleDebug       # -> app/build/outputs/apk/debug/app-debug.apk
```

Notes:
- `minSdk = 35`, `targetSdk = 36` (Android 16 primary, Android 15 minimum).
- `namespace` / `applicationId` = `it.luigi.macsync`.
- Release build uses R8 (`isMinifyEnabled = true`). If R8 strips something
  needed by `NotificationListenerService`, add a keep rule to
  `app/proguard-rules.pro`.

### Non-interactive SDK setup (if the SDK is missing)

```sh
# command line tools + platform-tools
brew install --cask android-commandlinetools android-platform-tools
export ANDROID_HOME=/usr/local/share/android-commandlinetools
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

## Reproducing the upstream baseline

```sh
git clone https://github.com/LK024/PixelMacSync.git
cd PixelMacSync
git rev-parse HEAD   # e5a5c060111014f27dba5af18173f61d3c55c5c8
```
