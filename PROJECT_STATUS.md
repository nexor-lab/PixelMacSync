# PROJECT_STATUS.md — PixelMacSync macOS 12.7 / Intel port

Last updated: 2026-10-03 (v2.2-beta published; Apple Silicon M4 verified)
Branch: `macos12-port` — **v2.2-beta pushed** (`0c78cf3`)
State: **RUNNING** (autonomous; marker cleared)

## v2.2-beta publication + M4 verification — RESULT (2026-10-03)

| Item | Status |
|---|---|
| Beta release `v2.2-beta` (prerelease) with APK + Universal DMG + 3 screenshots | **PASS** |
| Asset names on the release | **FIXED** — were `PixelMacSync-.apk` / `…macOS12-.dmg`, re-uploaded correctly |
| Release notes | **PASS** — now state Apple Silicon (M4) verified |
| macOS Universal on Apple Silicon (M4, macOS 26.3) | **PASS** — native arm64, no Rosetta, notifications + BLE, no crash |
| Bundle version string | **LIMITATION** — still `2.0` (bump pending) |
| Gatekeeper | **LIMITATION** — ad-hoc, not notarized (right-click open) |
| Banner click / inline reply UI / media on M4 | **NOT TESTED** — GUI scripting unavailable (no screen recording TCC) |

## Android authorization method (root / Shizuku) — RESULT (2026-10-03)

| Item | Status |
|---|---|
| Collapsible “Authorization method” card (Root / Shizuku radios, status, icon) | **PASS** — light + dark |
| Root status | **PASS** — Available |
| Shizuku status + grant flow | **PASS** — Installed / Running / Granted |
| Shizuku official GitHub link + install hint | **PASS** |
| `PrivilegeManager` unifies `su` / Shizuku; persisted selection | **PASS** |
| Hotspot enable via Shizuku (`cmd wifi start-softap`) | **PASS** — AP interface `wlan2` up |
| Hotspot disable via Shizuku | **PASS** — `HOTSPOT_STATE OFF` |
| `Shizuku.newProcess` reflection + threaded read (no `waitFor`) | **PASS** |
| About card (desc + version + repo link) | **PASS** |
| Long-press title easter egg | **PASS** |
| Collapse animation (no rubber-band / residual height) | **PASS** |
| Grant button left-aligned with the Shizuku link | **PASS** |

## Notification behavior + NotifyTest — RESULT (2026-10-03, real device)

| Item | Status |
|---|---|
| Webpage/deep-link jumping removed (package→app mapping only) | **PASS** — `url` field gone; legacy URL mappings dropped |
| Banner click does not clear the phone notification | **PASS** — KILL only on Notification-Center dismiss |
| Reply capability flag (`POST` 7th field `replyable`) | **PASS** — parser tests include it |
| Mac shows Reply field only for `replyable=1` (two categories) | **PASS** — build + code |
| Android test harness `:notifytest` (plain + RemoteInput chat) | **PASS** — builds/installs/runs |
| Mac inline reply → Android RemoteInput (test app) | **PASS** — `REPLY_RESULT ok`, phone received it |
| Real X notification | **PASS** forwarded; no reply action (`canReply=0`) |
| Noisy `W Bundle` warnings | **PASS** — removed |
| Real WeChat reply | **NOT TESTED** — needs an incoming WeChat message |
| Notification-Center dismiss → KILL | **NOT TESTED** — needs a manual swipe |

## Notification inline reply — RESULT (2026-10-03, POC)

Reply to a phone notification from the Mac via the origin app's `RemoteInput`
(public API, BLE-only). Protocol: Mac→Android `REPLY US <id> US <base64(text)>`;
Android→Mac `REPLY_RESULT US <id> US ok|not_found|no_reply_action|error`.

| Item | Status |
|---|---|
| Android `REPLY` command + base64 decode (`GattServerManager`) | **PASS** — release APK built |
| Android `RemoteInput` send (action lookup + `addResultsToIntent` + `send`) | **PASS** — code / build |
| Android `REPLY_RESULT` feedback | **PASS** — code |
| macOS `parseReplyResult` | **PASS** — parser tests **66/0** |
| macOS `UNNotificationCategory` + `UNTextInputNotificationAction` registration | **PASS** — build + launch smoke |
| macOS `didReceive` userText → `REPLY` (MTU-capped, multi-byte safe) | **PASS** — code |
| Reply failure surfaced to the user | **PASS** — code |
| **Real-device RemoteInput on WeChat / Telegram / SMS** | **NOT TESTED** — no device attached |
| Inline reply UI on ad-hoc build | **LIMITATION** — requires Apple-signed native notification; ad-hoc uses `osascript` (no custom actions) |
| Apps without a free-form reply action (e.g. many social feeds) | **LIMITATION** — returns `no_reply_action` |

## Notification click-to-open — RESULT (2026-10-03)

Clicking a Mac banner resolves the target: explicit `url` from the payload →
`app_mappings.json` (app name **or** URL) → `NSOpenPanel` app picker. The
`POST` 7th `url` field is optional/best-effort (Android taps are opaque
`PendingIntent`s).

| Item | Status |
|---|---|
| Android `POST` optional 7th `url` field (best-effort extras scrape) | **PASS** — release APK built |
| macOS `Protocol.parseNotification` reads optional `url` | **PASS** — parser tests **60/0** |
| `app_mappings.json` value as URL (`open <url>`) vs app name (`open -a`) | **PASS** — build + unit tests |
| Click priority url > mapping > picker | **PASS** — code path |
| Default map seeds `com.twitter.android → https://x.com/notifications` | **PASS** |
| Real-device click test (WeChat / X / Telegram) | **NOT TESTED** |
| Exact post/chat deep link | **LIMITATION** — `PendingIntent` not serialisable; only links present in extras |

## Music control — RESULT (2026-10-02)

Phone now-playing → Mac (title/artist/album/cover/progress) and remote control
(play/pause/next/prev/seek), **BLE-only + event-driven (no polling)**, reusing
the existing `NotificationListenerService` identity (no new permission).

| Item | Status |
|---|---|
| Android `MediaSessionMonitor` (active sessions + metadata/playback callbacks) | **PASS** |
| Android `MUSIC_META` + `ART_BEGIN/DATA/END` protocol | **PASS** |
| Android `MUSIC_*` command handling (`GattServerManager`) | **PASS** |
| macOS `Protocol.swift` music + cover parsing | **PASS** — parser tests **50/0** |
| macOS `MusicState` + cover cache `~/Pictures/MacSyncCovers/<key>.jpg` | **PASS** — 3 tracks cached (~9–11 KB) |
| Cover updates on rapid track skip (stale-cover fix) | **PASS** — in-flight cover cancelled, final sent once, key matched |
| macOS popover music UI under Remote Hotspot (cover/slider/transport) | **PASS** |
| Android release APK (R8 + zipalign + apksigner) | **PASS** — `PixelMacSync-Android.apk` 2.5 MB |
| macOS x86_64 build + (optional) local Apple re-sign | **PASS** — release package uses ad-hoc signing |
| **Music metadata phone → Mac** | **PASS** (real device, NetEase Cloud Music) |
| **Remote playback control (play/pause/next/prev)** | **PASS** (via `pixelsync://music/...` BLE hook) |
| **Seek** | **PASS** (`MUSIC_SEEK60000` applied) |
| **Cover art transfer + cache** | **PASS** (stale-cover fix verified on rapid skip) |
| **Phone media-volume slider (Mac → phone / phone → Mac)** | **PASS** (`MUSIC_VOLUME` / `MUSIC_VOLUME_SET`; hardware keys sync) |
| **Lyrics** | **LIMITATION** — no standard Android API, project is offline-only |

## Remote hotspot + login autostart — RESULT

| Item | Status |
|---|---|
| Remote Hotspot Enable (Mac→BLE→Root→real ON) | **PASS** |
| Remote Hotspot Disable | **PASS** |
| Hotspot State Sync (real, no optimistic UI) | **PASS** |
| Credentials (no log/BLE/report/hardcode) | **PASS** |
| NAT/internet sharing via shell surface | **LIMITATION** (HOTSPOT.md) |
| Stable hotspot SSID (reuses the phone's saved SSID) | **PASS** |
| Password reveal for one-time macOS save | **PASS** (Android app shows it; never logged/BLE) |
| macOS Login Auto Start | **PASS** — **enabled by default** (app self-registers the LaunchAgent at first launch; no manual step) |
| Menu-bar-only startup | **PASS** |
| Notification header = sender app name | **PASS** (banner: `<App>` / original title / body) |
| Android hotspot profile text removed | **PASS** |
| Apple signing (optional, local) | **PASS** — local Apple Development cert; `codesign --verify` valid. **Not used in the public release package** (ad-hoc instead). |
| Native notifications unlocked (`UserNotifications` auth) | **PASS** — `concessi: true, errore: nil` (was false pre-signing) |
| BLE app-icon transfer + cache | **PASS** — `~/Pictures/MacSyncIcons/{test.notifier,com.twitter.android}.png` |
| Resign tooling (`scripts/resign_macos.sh`) | **PASS** — auto-imports WWDR G3/G4, signs, verifies |
| Native banner visual confirmation | **NOT TESTED** (screen capture kept hitting the OpenCode Space) |

## Remote hotspot + login autostart (this request)

| Item | Status |
|---|---|
| Hotspot BLE protocol (`HOTSPOT_ENABLE/DISABLE/STATUS`, `HOTSPOT_STATE/ERROR`) | PASS (build) |
| Android root control (`cmd wifi start-softap/stop-softap`, saved config, no password leakage) | PASS (command verified via root) |
| Android root grant for the app (SukiSU allowlist) | PASS (`it.luigi.macsync` present) |
| Mac hotspot UI (state machine, no optimistic ON) | PASS (build + typecheck) |
| Mac receives real state (`Hotspot STATE -> OFF`) over BLE | PASS |
| **Remote Hotspot Enable end-to-end** | **BLOCKED** (needs macOS Accessibility to automate the click, or a manual click) |
| **Remote Hotspot Disable end-to-end** | **BLOCKED** (same) |
| macOS Login Auto Start (LaunchAgent) | PASS (build); activation test = PENDING |
| Menu-bar-only startup (no window) | PASS (LSUIElement already) |

See **HOTSPOT.md** for the full mechanism and limitations.

## Background lifecycle validation — RESULT

```
===== BACKGROUND LIFECYCLE VALIDATION =====
Home: PASS
Recents Swipe: PASS
Recents Swipe + BLE: PASS
Recents Swipe + Notification: PASS
Recents Swipe + Screen Off: PASS
Recents Swipe + Reconnect: PASS
NotificationListener Rebind: PASS
TelephonyCallback Background: NOT TESTED
Process Kill (SIGKILL): PASS
Force Stop: LIMITATION (system-controlled; not bypassed)
Boot Recovery: PASS (root watchdog in /data/adb/service.d)
HyperOS Background: PASS (with listener-rebind caveat)
Android 15 FGS (connectedDevice): PASS
Regression BLE: PASS
Regression Notification: PASS
Regression Doze: PASS
Regression Reconnect: PASS
2h Long Run: NOT TESTED
=============================================
```

Real evidence after a full reboot: the service.d watchdog revived the app +
notification listener, the Mac auto-reconnected, and a **real WeChat
notification** was synced over BLE (`POST ricevuto ... pkg=com.tencent.mm`).

## Components

| Area | Status |
|---|---|
| macOS app (`/Applications/PixelSync.app`, x86_64 MacOS12) | PASS |
| macOS localization (zh/en) + connection-state enum | PASS |
| macOS notification fallback (osascript → Notification Center) | PASS |
| Android APK (localized, minSdk 35 / targetSdk 36) | PASS |
| Android lifecycle (START_STICKY, onTaskRemoved, listener rebind, MY_PACKAGE_REPLACED) | PASS |
| SukiSU root watchdog (`/data/adb/service.d/pixelsync_watchdog.sh`) | PASS (installed + running) |
| Automation safety infra (`scripts/warning.sh`, `AUTOMATION_STATE.md`) | PASS |

## Root watchdog behavior (installed)

- Runs at boot via `/data/adb/service.d/` (`ksud 4.2.0`).
- 60 s healthy poll; backoff 5/10/20/40/80/120 s; idle 300 s after 8 failures.
- Graded recovery: L3 FGS start → L4 activity → L2 listener rebind
  (`cmd notification disallow/allow`); BLE reconnect handled in-app.
- **Does not fight Force Stop** (detects `User 0: stopped=true` → LIMITATION).
- Single-instance lock (verified via `/proc/<pid>/cmdline`), rotates its log.
- Log: `/data/adb/pixelsync_watchdog.log`.

## Device / environment

- Android test phone, Android 15 / API 35, HyperOS OS3.0.
- SukiSU Ultra root; `su` works (`u:r:ksu:s0`); ADB root enabled.
- **Note:** ADB USB authorization is lost after each reboot and must be
  re-confirmed on the phone (environment behavior).

## Known limitations

1. **Force Stop** cannot be recovered (Android user control) — by design.
2. **Incoming-call** states need a second phone — NOT TESTED.
3. HyperOS notification-listener "enabled ≠ live" — handled by rebind/watchdog.
4. macOS UserNotifications refuses non-Apple-signed apps → fallback used.
5. 2 h / 8 h long-run + power — NOT TESTED.

## Next safe actions

- Run incoming-call tests with a second phone.
- 2 h (then 8 h) idle long-run + power observation.
- Optional: sign macOS app with an Apple cert to restore native notifications.
- (On user approval) `git commit` the verified state.
