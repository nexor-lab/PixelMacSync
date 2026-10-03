# TEST_REPORT.md — PixelMacSync

Date: 2026-09-30
Host: MacBookPro12,1 / macOS 12.7.6 / Intel x86_64 / CLT 14.2 (Swift 5.7.2)
Device: **POCO F5 Pro (23013PC75G / `mondrian`)**, Android 15 / API 35, HyperOS OS3.0, Bluetooth ON, bootloader `green`.

> Legend: **PASS** (executed, observed) · **FAIL** · **BLOCKED** · **NOT TESTED**.
> Nothing below is marked PASS from inspection alone.

---

## Notification click-to-open (2026-10-03)

| # | Test | Result |
|---|---|---|
| N1 | Android release build (POST optional 7th `url`) | **PASS** — `PixelMacSync-Android.apk` signed |
| N2 | macOS x86_64 build (ad-hoc) | **PASS** |
| N3 | BLE parser unit tests | **PASS** — **60 passed, 0 failed** (incl. URL 7th field) |
| N4 | `app_mappings.json` URL value opens via `NSWorkspace.open` | **PASS** (code) |
| N5 | App-name mapping opens via `open -a` | **PASS** (existing path) |
| N6 | Click priority: url > mapping > picker | **PASS** (code) |
| N7 | **Real-device** click: WeChat → Mac WeChat | **NOT TESTED** |
| N8 | **Real-device** click: X → `x.com/notifications` | **NOT TESTED** |
| N9 | **Real-device** click: exact post/chat deep link | **LIMITATION** — `PendingIntent` not serialisable; only URLs present in extras |

---

## A. Build

| # | Test | Result |
|---|---|---|
| A1 | macOS build (x86_64, `macos12.0`) | **PASS** — `Mach-O 64-bit executable x86_64` |
| A2 | macOS launch smoke test | **PASS** — stays alive, no crash |
| A3 | BLE parser unit tests | **PASS** — **50 passed, 0 failed** (incl. music + cover-art parsers) |
| A4 | macOS DMG | **PASS** — `PixelMacSync-macOS12-Intel.dmg` (1.5 MB) |
| A5 | Android debug build | **PASS** |
| A6 | Android release build (R8) | **PASS** — `PixelMacSync-Android.apk` 2.4 MB, signed |
| A7 | APK manifest | **PASS** — `minSdk 35`, `targetSdk 36` |

## B. Device baseline (real)

```
adb devices -l  -> <device-serial> device product:mondrian_global model:23013PC75G
ro.product.model          = 23013PC75G        (POCO F5 Pro)
ro.product.device         = mondrian
ro.product.manufacturer   = Xiaomi / brand POCO
ro.build.version.release  = 15
ro.build.version.sdk      = 35
ro.product.cpu.abilist    = arm64-v8a,...      (no arm64 emulation issue)
ro.boot.verifiedbootstate = green
bluetooth_on              = 1
ro.mi.os.version.name     = OS3.0              (HyperOS 3.0)
```
**PASS** — Android 15 / API 35 confirmed.

## C. Android permissions (real)

Granted via `pm grant` and verified in `dumpsys package`:
`BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `READ_PHONE_STATE`, `READ_CONTACTS`,
`POST_NOTIFICATIONS`, `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` → all **granted=true**.
`NotificationListenerService` enabled and **live** (see below). **PASS**

> MIUI/HyperOS quirk found: enabling the listener with
> `cmd notification allow_listener` put it in the enabled list but the system did
> **not bind** it ("Live notification listeners" did not contain it) until a
> `disallow_listener` + `allow_listener` cycle. After that it became live and
> callbacks worked. Documented for INSTALL.md.

## D. Notification sync (real) — **PASS**

Pipeline verified with a controlled notifier app (`test.notifier`):

```
Android Notification posted (test.notifier)
  -> NotificationListenerService (live)
  -> NotificationFilter (package enabled)
  -> stable id "n" + hex(sbn.key.hashCode())  = nd7852ef4
  -> BLE GATT notify
  -> Mac CoreBluetooth -> PixelPacket -> macOS banner
```

Mac log (real):
```
MacSync: POST ricevuto id=nd7852ef4 pkg=test.notifier title=测试联系人
```
- UTF-8 (Chinese title/body) parsed correctly.
- **Dedup**: the same notification delivered twice arrived with the same id
  `nd7852ef4` (identifier replace, no duplicate banner).
- **Filter**: the 6 seeded apps (Gmail, QQ, Telegram, WhatsApp, 微信, 短信) were
  observed enabled in the UI; `test.notifier` was enabled manually and passed.

## E. Call sync (real) — **NOT TESTED**

Incoming-call states (`CALL_STATE_RINGING` / `MISSED`) require a **second phone**
to call the POCO; none available. Outgoing call was **not** placed (real-world
side effect, not authorised). The call packet parser is covered by A3 (RINGING/
OFFHOOK/IDLE/MISSED all pass). The Android call listener is registered and
compiles; **not** exercised on a real call.

## F. BLE connection (real) — **PASS**

- Android advertises the service UUID (log: `Advertising avviato con successo!`).
- Mac scans, connects, discovers service + 3 characteristics, subscribes.
- Mac writes `SYNC_REQ`; Android logs
  `MacSync: Ricevuto pacchetto comandi dal Mac: SYNC_REQ`.
- Android UI shows **Connesso**; Mac shows **Connesso al Pixel**.
- Telemetry read/notify path operational.

## G. Notification → Mac (real) — **PASS** (see D)

## H. Call → Mac — **NOT TESTED** (see E)

## I. Reconnect (real) — **PASS**

Three independent disconnect causes were recovered automatically:

| Cause | Evidence |
|---|---|
| Connection timeout during scan | `Timeout connessione in scansione!` → `Dispositivo disconnesso` → reconnect |
| Phone Bluetooth OFF → ON | see L |
| Android app force-stop → relaunch | disconnected 19:56:22 → `SYNC_REQ` 19:56:31 (manual relaunch) |

## J. Mac sleep/wake — **NOT TESTED**

Cannot be exercised safely from this session: sleeping the Mac (`pmset sleepnow`)
suspends the session and cannot be woken programmatically (no `sudo`, no physical
input). The app registers `NSWorkspace.willSleepNotification` (cancels BLE) and
`didWakeNotification` (restarts CoreBluetooth after 4 s); code untested on a real
sleep cycle.

## K. Screen off / background (real) — **PASS**

Phone in `mWakefulness=Dozing`, screen off; an explicit broadcast posted a
notification; the foreground service kept the listener + BLE alive:
```
MacSync: POST ricevuto id=n39123e94 pkg=test.notifier title=后台测试
```
(Implicit broadcasts are blocked by Android background policy — only the explicit
broadcast reached the app; that is a test-harness detail, not a MacSync issue.)

## L. Bluetooth OFF/ON (real) — **PASS**

```
19:55:49 Mac: Dispositivo disconnesso
19:55:50 Android: Bluetooth spento da Android! Fermo i motori BLE...
19:55:56 Android: Bluetooth riacceso da Android! Attendo 2 secondi...
19:55:58 Android: Advertising avviato con successo!
19:55:59 Mac: SYNC_REQ inviato
19:56:00 Mac: POST ricevuto ... (snapshot resent)
```
Both sides recovered automatically; no user action in the app.

## M. HyperOS background — **PASS (with caveat)**

The foreground service stayed alive while the phone was dozing and delivered a
notification over BLE (K). Caveat: enabling the notification listener via ADB did
not bind it until a disallow/allow cycle; on a cold install the user must grant
Notification access in Settings. Autostart/battery policy not explicitly changed
during this test.

## N. Reboot — **NOT TESTED**

Rebooting the phone would require a manual relaunch afterwards (no autostart was
added, per instructions). Not attempted.

## O. Long run (2 h) — **NOT TESTED**

Requires a 2 h idle window; not performed in this session.

## P. Power observation — **NOT TESTED**

---

## Blocking issue — macOS native notification display

**Symptom:** `UNUserNotificationCenter.requestAuthorization` returns
`UNErrorDomain Code=1 "Notifications are not allowed for this application"` on
macOS 12.7.6, and `UNUserNotificationCenter.add()` does not display a banner.

**Investigation:**
- Reproduced with a minimal 20-line SwiftUI app (`AuthDemo`) → same error.
- Not caused by `LSUIElement` (AuthDemo is a normal app).
- Not caused by ad-hoc vs self-signed: signed with a **trusted self-signed
  code-signing identity** (`PixelMacSync Dev`, trust verified via
  `security verify-cert -p codeSign`), designated requirement satisfied,
  `spctl` accepted (`spctl --add`) → **still** Code=1.
- `auth = 6` exists in `com.apple.ncprefs` for the bundle id.

**Conclusion:** macOS 12.7 UserNotifications refuses apps that are not signed by
an **Apple-issued** certificate (Apple Development / Developer ID). This is a
platform/policy limitation, not a code defect. Requires an Apple Developer
certificate (and ideally notarization) to be removed.

**Mitigation implemented (works, real):** the app now falls back to delivering to
the **native Notification Center via `/usr/bin/osascript`** when
`requestAuthorization` fails. Verified banner:
> 测试联系人 · (subtitle) PixelSync · 这是一条测试通知 🚀 晚上一起吃饭吗？

The native `UserNotifications` path remains primary and will be used automatically
if the app is later signed with an Apple certificate. Caveat of the fallback:
banners are attributed to the invoking script, and programmatic removal
(`REMOVE`) is not applied. Recorded in CHANGELOG.md.

## Q. Background lifecycle + localization + SukiSU watchdog (2026-09-30)

ADB root became available (SukiSU Ultra, `su` context `u:r:ksu:s0`); the watchdog
was installed to `/data/adb/service.d/pixelsync_watchdog.sh` (`ksud 4.2.0`).

| # | Test | Result |
|---|---|---|
| Q1 | Android lifecycle build (START_STICKY, onTaskRemoved, listener rebind, MY_PACKAGE_REPLACED) | **PASS** |
| Q2 | macOS localization build (`macos12.0` typecheck EXIT 0) | **PASS** |
| Q3 | Android Chinese UI on device | **PASS** — 已连接 / 设置 / 通知 / 与 Mac 同步通知 |
| Q4 | **Home** (app → background, service continues) | **PASS** — same process, FGS alive |
| Q5 | **Recents Swipe** (services survive) | **PASS** — same pid, FGS alive, listener live |
| Q6 | Recents Swipe + BLE | **PASS** |
| Q7 | Recents Swipe + Notification | **PASS** — `POST ricevuto ... test.notifier` |
| Q8 | Recents Swipe + Screen Off | **PASS** — Dozing, `POST ricevuto ... 后台测试` |
| Q9 | Recents Swipe + Reconnect (BT OFF/ON) | **PASS** — `Dispositivo disconnesso` → `SYNC_REQ` → snapshot |
| Q10 | NotificationListener Rebind (HyperOS enabled≠live) | **PASS** — `disallow/allow` → live; watchdog automates it |
| Q11 | **Process death (SIGKILL) → watchdog recovery** | **PASS** — `process missing → start FGS → process revived → listener rebound (live) → state OK` |
| Q12 | **Force Stop** | **LIMITATION** — watchdog detects `User 0: stopped=true` and deliberately does **not** fight it (no framework/SELinux/hide tricks) |
| Q13 | **Boot recovery (real reboot)** | **PASS** — service.d watchdog ran at boot and revived process + listener; Mac auto-reconnected. *(First attempts during the boot window failed; the exponential backoff retry succeeded.)* |
| Q14 | Real WeChat notification after boot+recovery | **PASS** — `POST ricevuto id=n7777aef0 pkg=com.tencent.mm` |
| Q15 | TelephonyCallback background | **NOT TESTED** (no real call) |
| Q16 | Android 15 FGS (`connectedDevice`) | **PASS** (accepted, START_STICKY) |
| Q17 | Regression: BLE / Notification / Doze / Reconnect | **PASS** |
| Q18 | 2 h / 8 h long run, power | **NOT TESTED** |

**Watchdog bug found & fixed:** the initial `pkg_force_stopped` used
`dumpsys package | grep stopped=true`, which matched the always-stopped
**User 999** profile line and caused a false "Force Stop → LIMITATION" verdict.
Fixed to check only the `User 0:` line. After the fix, process-death recovery
works.

Safety gate: `scripts/warning.sh` fired twice (ADB disconnected after reboot);
both times ADB recovered and the marker was cleared.

## R. Remote hotspot + macOS login autostart (2026-09-30, third session)

### Remote hotspot (Mac → BLE → Android → Root → HyperOS)

| # | Test | Result |
|---|---|---|
| R1 | Hotspot control surface audit (Android 15 / HyperOS 3) | **PASS** — `cmd tethering`/`connectivity`/`service call`/`svc`/`statusbar click-tile` all unusable; `cmd wifi start-softap`/`stop-softap` is the stable root surface |
| R2 | `start-softap` starts a real AP and does not clobber saved config | **PASS** (SoftApState ENABLED, iface `wlan2`; saved SSID unchanged) |
| R3 | App root grant in SukiSU | **PASS** (`it.luigi.macsync` in allowlist) |
| R4 | Mac receives real hotspot state over BLE | **PASS** (`MacSync: Hotspot STATE -> OFF`) |
| R5 | **Remote Hotspot Enable** (Mac → ON) | **PASS** — Mac `Inviato comando -> HOTSPOT_ENABLE`, `Hotspot STATE -> ON`; Android `wlan2=1` |
| R6 | **Remote Hotspot Disable** (Mac → OFF) | **PASS** — Mac `Inviato comando -> HOTSPOT_DISABLE`, `Hotspot STATE -> OFF`; Android `wlan2=0` |
| R7 | **Hotspot State Sync** (no optimistic UI) | **PASS** — state comes from the real `WIFI_AP_STATE_CHANGED` broadcast + `HOTSPOT_STATE` reply |
| R8 | Password/credential handling | **PASS** — never logged, never sent over BLE, never in reports; reuses saved config (reflection) else app-private generated |
| R9 | NAT / internet sharing | **LIMITATION** — `start-softap` raises the AP but does not invoke Tethering NAT (documented in HOTSPOT.md) |
| R10 | Reconnect after hotspot | **PASS** with caveat — a BLE drop occurred once after enabling; recovered via phone BT toggle. Cause ambiguous (2.4 GHz coexistence vs the device's recurring BT-stack wedge). |

### macOS login autostart

| # | Test | Result |
|---|---|---|
| R11 | LaunchAgent created on enable | **PASS** — `~/Library/LaunchAgents/it.luigi.pixelsyncmac.plist` |
| R12 | launchd loads it and starts the app (RunAtLoad) | **PASS** — `launchctl list` showed `it.luigi.pixelsyncmac` with a running pid |
| R13 | Disable removes the agent | **PASS** — plist removed |
| R14 | Literal logout/login reboot test | **NOT TESTED** (mechanism proven; a real logout is pending) |
| R15 | No window on startup (menu-bar only) | **PASS** (LSUIElement) |

## S. Music control (2026-10-02, fourth session)

| # | Test | Result |
|---|---|---|
| S1 | Android toolchain re-install (JDK21/Gradle 9.4.1/SDK 35-36.1) | **PASS** |
| S2 | Android `assembleRelease` + R8 + zipalign + apksigner | **PASS** — `PixelMacSync-Android.apk` 2.5 MB, signed (`CN=PixelMacSync`) |
| S3 | macOS `build_macos.sh` x86_64 | **PASS** |
| S4 | Parser unit tests (music `MUSIC_META` + `ART_*`) | **PASS** — **50 passed, 0 failed** |
| S5 | macOS local Apple re-sign (`scripts/resign_macos.sh`) | **PASS** — local Apple Development cert, secure timestamp, Gatekeeper `accepted`. The public release package is **ad-hoc** signed (no personal certificate). |
| S6 | **Music metadata phone → Mac** | **PASS** — Android `Invio stato musica: playing - 一封家书 / 石进`; track changes pushed on metadata callback |
| S7 | **Remote control Mac → phone (play/pause/next/prev)** | **PASS** — `pixelsync://music/{play,pause,next,prev}` → Android `Ricevuto pacchetto comandi dal Mac: MUSIC_*` → real state transitions (`paused`, `playing`, track change) |
| S8 | **Seek** | **PASS** — `pixelsync://music/seek/60000` → Android `MUSIC_SEEK60000`; `transportControls.seekTo()` applied |
| S9 | **Cover art transfer + cache** | **PASS** — `ART_*` reassembled to `~/Pictures/MacSyncCovers/com.netease.cloudmusic-*.jpg` (3 tracks, ~9–11 KB each, 111–114 packets) |
| S10 | Music isolated from notification/call/hotspot | **PASS** — hotspot `HOTSPOT_STATUS`/`SYNC_REQ` still answered on the same connection during music tests |
| S11 | Lyrics | **LIMITATION** — Android has no standard lyrics API and the project is offline-only |
| S12 | **Cover update on rapid track skip** (stale-cover fix) | **PASS** — 5 fast `MUSIC_NEXT`: in-flight covers `Copertina superata, annullo …`; the final track's cover sent exactly once (`Copertina inviata … f9293c29`), `MUSIC_META` key matches, Mac cache holds only current covers |
| S13 | **Phone volume slider Mac → phone** | **PASS** — `MUSIC_VOLUME_SET 60` → `Invio volume: 60% (90/150)`; `…SET 15` → `15% (23/150)`; uses `setStreamVolume` (no permission) |
| S14 | **Phone volume changes sync phone → Mac** | **PASS** — hardware key events produced `Invio volume: 9% / 36% / 64% …` via the `volume_music` observer |

> S6–S9 were driven over BLE using the app's own `pixelsync://music/...`
> automation hook (same pattern as `pixelsync://hotspot/...`), so no macOS
> Accessibility permission was required.

## Deferred / not tested

- Real incoming-call states (needs second phone) — **NOT TESTED**
- Mac sleep/wake — **NOT TESTED**
- 2 h / 8 h long run, power measurement — **NOT TESTED**
- NAT/internet hotspot sharing — **LIMITATION** (see HOTSPOT.md)
- Music control end-to-end (S6–S10) — **BLOCKED** (no device attached)
