# COMPATIBILITY.md — macOS 12.7 / Intel port audit

## 0. 适配范围（Scope，本 Fork）

| 端 / 项 | 支持范围 | 实测 |
|---|---|---|
| **macOS** | **12.7 Monterey 及以上**（Intel x86_64；`ARCH=arm64`/`universal` 可构建） | MacBookPro12,1 / 12.7.6 |
| **Android** | **15（API 35）及以上**；`compileSdk`/`targetSdk` **36** | POCO F5 Pro / HyperOS 3.0 |
| **Root** | 需要（SukiSU/Magisk）以真实开关热点；**不需要 MacroDroid** | SukiSU Ultra |
| **连接** | 仅 **BLE 4.2+**（不使用 Wi‑Fi/局域网/云/TCP） | — |
| **功能** | 通知 / 电话事件 / 远程热点 / App 图标同步 / **音乐控件（含音量）** / 遥测 | 真机 PASS |

> 对比上游：上游要求 **macOS 13+ / Android 16+ / MacroDroid**；本 Fork 已下移适配并移除 MacroDroid 依赖。

Target: **macOS 12.7 Monterey, Intel x86_64, MacBookPro12,1**
Toolchain available: **Command Line Tools 14.2 only (Swift 5.7.2, SDK 13.1)** —
*no full Xcode*.

## 1. macOS 13+ API audit (upstream → this port)

| Location | Upstream API | Min OS | Conflict | Fix applied |
|---|---|---|---|---|
| `PixelSyncApp.swift:18` | `MenuBarExtra` | **13.0** | App would not launch on 12.7 | Rewritten with **AppKit `NSStatusItem` + `NSPopover`** |
| `PixelSyncApp.swift:21` | `.menuBarExtraStyle(.window)` | **13.0** | Same | Removed with the rewrite |
| `ContentView.swift:87` | `.onChange(of:) { old, new in }` | **14.0** | Does not compile for < 14 | Changed to single-parameter `{ newValue in }` (macOS 11+) |
| `ContentView.swift:57` | `Image(systemName: "cellularbars", variableValue:)` | SF Symbols 4 / 13 | Symbol blank on 12 | `if #available(macOS 13.0, *)` branch; fallback plain symbol |
| `project.pbxproj` | `MACOSX_DEPLOYMENT_TARGET = 26.4` | — | Non-existent/hostile OS gate | Replaced by a CLT `swiftc` build (`-target x86_64-apple-macos12.0`) |
| `project.pbxproj` | `objectVersion 77`, `LastUpgradeCheck 2650` | Xcode 26 | Xcode 14 cannot open it | Not used; `build_macos.sh` drives the build |
| build settings | `SWIFT_DEFAULT_ACTOR_ISOLATION=MainActor`, `SWIFT_APPROACHABLE_CONCURRENCY=YES` | Swift 6 | Swift 5.7 cannot parse | Not used |

**Everything else is macOS 12-compatible**: `CoreBluetooth`, `UserNotifications`,
`Combine`, `AppKit`, `UniformTypeIdentifiers`. No macOS-13-only Bluetooth or
notification API is used.

## 2. Port strategy

Because a full Xcode is not installed (and Xcode 26 will not run on 12.7), the
app is built directly with the Command Line Tools:

```
swiftc -parse-as-library -O -whole-module-optimization \
       -target x86_64-apple-macos12.0 -sdk <CLT SDK> \
       -o PixelSync.app/Contents/MacOS/PixelSync BLEManager.swift ContentView.swift PixelSyncApp.swift
```

The `.app` bundle, `Info.plist` (with `LSUIElement=YES`,
`NSBluetoothAlwaysUsageDescription`) and ad-hoc code signature are assembled by
`MacOS/build_macos.sh`.

Verified: `file` reports `Mach-O 64-bit executable x86_64`; the bundle launches
and stays alive. See TEST_REPORT.md.

## 3. Architecture support

- Default build: `ARCH=x86_64` (this Mac).
- `ARCH=arm64 ./build_macos.sh` for Apple Silicon.
- `ARCH=universal ./build_macos.sh` builds a fat binary via `lipo`.

## 4. App Sandbox / entitlements

Upstream enables App Sandbox + Hardened Runtime with the Bluetooth entitlement.
The CLT build is **not sandboxed** (ad-hoc signed), which is acceptable for
personal use and still triggers the macOS Bluetooth (TCC) prompt. If you later
sign with a real identity, add:

```
com.apple.security.device.bluetooth = true
```

## 5. Android compatibility

| Setting | Upstream | This port | Reason |
|---|---|---|---|
| `compileSdk` | 36 (minor 1) | 36 (minor 1) | Keep Android 16 features |
| `targetSdk` | 36 | 36 | Primary line = Android 16 |
| `minSdk` | **36** | **35** | Requirement: core must run on Android 15 |
| Java source/target | 11 | 11 | unchanged |

All APIs used by the app are available on **API 35**:

| API | Since |
|---|---|
| `BluetoothGattServer`, `BluetoothLeAdvertiser` | API 21 |
| `TelephonyCallback.CallStateListener` | API 31 |
| `registerTelephonyCallback` | API 31 |
| `Context.RECEIVER_NOT_EXPORTED` / `RECEIVER_EXPORTED` | API 33 |
| `notifyCharacteristicChanged(device, char, confirm, value)` | API 33 |
| `POST_NOTIFICATIONS` runtime permission | API 33 |
| `NotificationListenerService` | API 18 |
| `ContactsContract.PhoneLookup` | API 5 |

Because `minSdk = 35`, none of the above needs a runtime version guard on
supported devices.

## 6. Known macOS 12 limitations (honest)

- **Sleep/wake BLE**: the app cancels the peripheral connection before sleep and
  restarts CoreBluetooth 4 s after wake. Whether macOS 12.7 restores the link
  automatically cannot be verified without a real phone; see TEST_REPORT.md.
- **Notification permissions** are requested at launch; on macOS 12 the app is
  unsandboxed and ad-hoc signed, so the permission may be tied to the signature
  and could need re-granting after a re-sign.
- `NSApp.activate(ignoringOtherApps:)` is deprecated in macOS 14 but present on
  12 — no warning-as-error in this build.
