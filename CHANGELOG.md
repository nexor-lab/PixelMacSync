# CHANGELOG.md

## [android-auth-method + about] — 2026-10-03

Android app: choose how privileged commands run (root or Shizuku), plus an
About section and a small easter egg. UI, protocol and macOS side untouched.

### Added

- **Authorization method card** (collapsible, below *Notifications*): one header
  row (shield icon + “Authorization method” + current method + chevron) that
  expands to Root / Shizuku radio options with live status.
  - **Root**: `su` (status = available/unavailable).
  - **Shizuku**: privileged shell without root; shows installed / running /
    granted state, the official GitHub link, an “install from this link” hint
    and a **Grant permission** button.
- **About card** at the bottom: short description, version and a link to the
  GitHub repository.
- **Easter egg**: long-press the “MacSync” title.
- **`PrivilegeManager`**: unified privileged shell (root `su` or Shizuku) with
  the method persisted in `MacSync_Prefs`; `HotspotController` now runs through
  it.
- Shizuku deps (`dev.rikka.shizuku:api` + `:provider`) and the `ShizukuProvider`
  manifest entry.

### Notes / fixes

- `Shizuku.newProcess` is **private** in API 13.1.5 (deprecated, removal planned
  in API 14), so it is called reflectively; a ProGuard keep rule preserves it.
- Avoid `Process.waitFor(timeout)`: `ShizukuRemoteProcess` throws
  `IllegalArgumentException("process hasn't exited")`; output is now read on a
  worker thread with a timeout (and stderr merged via `sh -c "… 2>&1"`).
- The collapse uses only `AnimatedVisibility` (no `animateContentSize`) so the
  content and the card frame animate together (no rubber-band / residual height).
- Light/dark supported (Material3 theme colors only).

### Verification (real test Android phone)

- Root: status **Available**.
- Shizuku: permission granted → status **Granted**.
- Hotspot **enable/disable while Shizuku is selected** → **PASS** (AP interface
  `wlan2` up, then `HOTSPOT_STATE OFF`; no `process hasn't exited` error).
- UI: light + dark rendering **PASS**; expand/collapse spacing/alignment fixed.

## [notification-behavior + notifytest] — 2026-10-03

### Behavior changes

- **No app picker on notifications at all.** The explicit "Open" action and the
  `NSOpenPanel` picker were removed, so neither a stray nor an intentional
  notification click can open a Finder-like panel. Add mappings by editing
  `~/Documents/MacSync/app_mappings.json`.
- **Removed webpage/deep-link jumping.** `POST` no longer carries a `url`, the
  Mac never opens an `http(s)` link, and `app_mappings.json` only accepts macOS
  app names (legacy `http(s)` values are dropped on load). Clicking a banner
  opens the mapped app by **package name**; unmapped packages do nothing.
- **Clicking a banner no longer clears the phone notification.** The phone side
  is cleared **only** when the banner is dismissed in Notification Center
  (`UNNotificationDismissActionIdentifier` → `KILL`). Reply also leaves it.
- **Reply capability is explicit.** The optional 7th `POST` field is now
  `replyable` (`1`/`0`): Android sets it when the notification exposes a
  free-form `RemoteInput`. The Mac shows the inline **Reply** field only for
  `1` (two notification categories), so the user can tell which notifications
  can be answered. Old 6-field payloads default to `0`.
- The **Open** action (kept) shows the `NSOpenPanel` picker only when no mapping
  exists and the user explicitly chose Open — never on a plain click.

### New: Android test harness (`Android/notifytest`, not shipped)

Separate debug app (`it.luigi.macsync.notifytest`):
- posts a **plain** notification with no reply action (must show no reply field);
- posts a **chat** notification with a real free-form `RemoteInput` reply action;
  the reply comes back to `ReplyReceiver` and is shown as a notification.

Driven by buttons or `adb shell am start … --es cmd post_x|post_chat|clear`.
Enable the app in PixelSync's notification list to have it forwarded.

### Fixed

- Removed noisy `W Bundle` runtime warnings from the old URL extras scan.

### Added

- `pixelsync://reply?to=<notifId>&text=<text>` URL hook (also automates
  inline-reply testing).
- `POST` log line now includes `canReply=`.

### Verification (real test Android phone + real Mac, BLE)

- Chat notification (`replyable=1`): Mac inline reply → Android `RemoteInput` →
  test app received it; `REPLY_RESULT ok`.
- Plain notification (`replyable=0`): forwarded; Mac chooses the no-reply
  category.
- Real X notification forwarded (no reply action → `canReply=0`).
- Real WeChat reply: **NOT TESTED** (needs an incoming message; the equivalent
  RemoteInput path is verified end-to-end).

## [notification-inline-reply] — 2026-10-03 (POC)

New feature: **reply to phone notifications from the Mac** via the origin app's
`RemoteInput` (public API, BLE-only). POC — plumbing + build; real-device
behaviour not yet verified.

### Android

- **`MacSyncNotificationListener.kt`** — new `REPLY_NOTIFICATION` receiver.
  `sendReply` matches the still-active `StatusBarNotification` by stable id,
  picks the action exposing free-form `RemoteInput`, injects the text with
  `RemoteInput.addResultsToIntent(...)` + `action.actionIntent.send(...)`, and
  replies `REPLY_RESULT US <id> US ok|not_found|no_reply_action|error`.
- **`GattServerManager.kt`** — new `REPLY US <id> US <base64(text)>` command;
  decodes base64 and broadcasts it to the listener.

### macOS

- **`Protocol.swift`** — `parseReplyResult` (`REPLY_RESULT`), unit-tested.
- **`BLEManager.swift`** —
  - registers a `UNNotificationCategory` with a `UNTextInputNotificationAction`
    (required for the banner to show a reply field) and tags message
    notifications with `categoryIdentifier`;
  - `didReceive` handles `UNTextInputNotificationResponse.userText` →
    `sendReply(notifId:text:)` (`REPLY US <id> US <base64>`), shrinking the text
    character-by-character to fit `maximumWriteValueLength(for: .withResponse)`;
  - surfaces non-`ok` `REPLY_RESULT` as a local failure banner.
- **`Localization.swift`** — reply / send / placeholder / failure strings (zh/en).

### Docs

- `BLE_PROTOCOL.md` / `README.md`: `REPLY`, `REPLY_RESULT`, and the two
  limitations (app must expose RemoteInput; inline UI needs a native
  Apple-signed notification, so the ad-hoc build falls back to `osascript`).

### Verification

- `MacOS/run_tests.sh` — **66 passed, 0 failed**.
- Android release build — **PASS**.
- macOS build + launch smoke test — **PASS**.
- **Real-device RemoteInput (WeChat / Telegram / SMS)** — **NOT TESTED**
  (no device attached; `adb devices` empty).

## [notification-click-open] — 2026-10-03

New feature: **clicking a Mac banner opens the matching local app or web page**.
BLE-only, backward compatible (the new field is optional).

### Android

- **`MacSyncNotificationListener.kt`** — `POST` may now carry an optional 7th
  field `url`, a best-effort `http(s)` deep link scraped from the notification
  extras (known keys first: `android.url`/`android.link`/`url`/`link` +
  text/sub-text/big-text/summary/info; then a scan of all `CharSequence` extras).
  The real tap target is an opaque `PendingIntent` and cannot be serialised, so
  the field is omitted when nothing is found. Shared `buildPostPayload` helper
  used by both live posts and reconnect snapshots.

### macOS

- **`Protocol.swift`** — `Notification` gains `url`; `parseNotification` reads
  the optional 7th field (empty on older payloads). Parser tests cover it.
- **`BLEManager.swift`** — `deliverNotification` forwards `url` in `userInfo`.
  On click the target resolves in priority order: explicit `url` → user mapping
  → app picker. `app_mappings.json` values may now be an `http(s)` URL (opened
  in the browser) in addition to a macOS app name (`open -a`). Default map seeds
  `com.twitter.android → https://x.com/notifications`.

### Docs

- `BLE_PROTOCOL.md` / `README.md` document the optional `url` field and the
  click-to-open resolution order + the best-effort deep-link limitation.

### Verification

- `MacOS/run_tests.sh` — **60 passed, 0 failed**.
- Android release build — **PASS**.
- macOS x86_64 build (ad-hoc) — **PASS**.
- Real-device (WeChat / X / Telegram) — **NOT TESTED**.

## [music-control] — 2026-10-02

New feature: **music control** (phone now-playing -> Mac display + remote
control), BLE-only, event-driven, no polling.

### Android

- **`MediaSessionMonitor.kt`** (new) — observes the active `MediaSession` using
  the **existing** `NotificationListenerService` identity
  (`MediaSessionManager.getActiveSessions(ComponentName)`), so no new permission
  is required. Pushes on change only:
  - `MUSIC_META US title US artist US album US durationMs US positionMs US state
    [US coverKey]` (`state` ∈ `playing|paused|stopped`; `US` = `\u001F`; the
    optional `coverKey` lets the Mac match/ignore covers per track).
  - Cover art once per track: `ART_BEGIN US key` / `ART_DATA US key US seq US
    <base64>` / `ART_END US key`. Re-encoded to JPEG (max 256 px, q82),
    single-threaded and paced at 18 ms/packet like the existing icon transfer.
  - **Cover-update fix**: skipping tracks quickly no longer leaves a stale
    cover. An in-flight transfer is cancelled when a newer key is requested
    (`requestedCoverKey`), the key is stable (title+artist, album excluded to
    avoid metadata-jitter churn), and an `activeCoverKey` guard prevents a
    metadata refresh for the same track from queuing a duplicate send.
  - **Media volume**: `MUSIC_VOLUME US <percent>` is pushed on change
    (`AudioManager.getStreamVolume(STREAM_MUSIC)` + a `Settings.System`
    `volume_music` observer, so the phone's hardware keys sync too);
    `MUSIC_VOLUME_SET US <percent>` sets it via `setStreamVolume` (no
    permission). `MUSIC_STATUS` now also re-sends the volume.
- **`MacSyncNotificationListener.kt`** — starts/stops `MediaSessionMonitor`
  on (dis)connect so media access follows the listener lifecycle.
- **`GattServerManager.kt`** — handles new Mac commands `MUSIC_PLAY`,
  `MUSIC_PAUSE`, `MUSIC_NEXT`, `MUSIC_PREV`, `MUSIC_SEEK US <ms>`,
  `MUSIC_STATUS`.

### macOS

- **`Protocol.swift`** — added `parseMusic` (`MUSIC_META`) and `parseArt`
  (`ART_BEGIN/DATA/END`) plus unit tests.
- **`BLEManager.swift`** — `MusicState` (`title/artist/album/durationMs/
  positionMs/isPlaying/state/coverKey/coverPath/updatedAt`), cover reassembly to
  `~/Pictures/MacSyncCovers/<key>.jpg`, control helpers
  (`musicPlay/Pause/Toggle/Next/Previous/Seek`), `MUSIC_STATUS` requested on
  every (re)connect, and state reset on disconnect/sleep/Bluetooth reset.
  On a track/key change it shows that key's cached cover instantly and
  **ignores any `ART_END` whose key is not the current track's** (drops stale /
  out-of-order art).
- **`ContentView.swift`** — new `MusicControlView` under the Remote Hotspot
  section: cover, title/artist/album, a draggable progress slider (locally
  extrapolated from `position + (now − updatedAt)`, `TimelineView` 1 Hz),
  previous / play-pause / next, and a phone-volume slider (same style; shown
  whenever connected).
- **`Localization.swift`** — zh/en strings for the music panel.
- **`PixelSyncApp.swift`** — extended the `pixelsync://` automation hook with
  `pixelsync://music/{play,pause,toggle,next,prev,status}`,
  `pixelsync://music/seek/<ms>` and `pixelsync://music/volume/<percent>`
  (mirrors the hotspot hook).
- **`scripts/resign_macos.sh`** — pass `--keychain "$TMP_KC"` explicitly to
  `codesign` when signing from a `.p12`, fixing
  `errSecInternalComponent` / "unable to build chain to self-signed root" seen
  after the first re-sign on macOS 12.

### Build / validation (2026-10-02)

- Android toolchain re-installed (Temurin JDK 21, Gradle 9.4.1, SDK
  platforms 35/36/36.1 + build-tools 36.0.0/36.1.0).
- `assembleRelease` + zipalign + apksigner → `PixelMacSync-Android.apk`
  (2.5 MB, signed) **PASS**.
- macOS `build_macos.sh` x86_64 **PASS**; parser tests **50 passed / 0 failed**.
- `/Applications/PixelSync.app` re-signed with the Apple Development cert
  (local Apple Development certificate, secure timestamp) **PASS**. The public
  release package is ad-hoc signed instead (no personal certificate).
- **Real-device music round-trip PASS** (test Android phone / NetEase Cloud Music):
  metadata (`playing - 一封家书 / 石进`), covers cached at
  `~/Pictures/MacSyncCovers/*.jpg` (~9–11 KB), and Mac-driven
  play/pause/next/prev/seek all observed on the phone via
  `pixelsync://music/...`. Lyrics: **LIMITATION** (no standard Android API,
  offline-only).

## [macos12-port] — 2026-09-30

Base: upstream `LK024/PixelMacSync` @ `e5a5c060111014f27dba5af18173f61d3c55c5c8` (branch `main`).
Branch: **`macos12-port`**. All changes are on top of the untouched upstream commit.

### macOS — macOS 13+ API removal (port to 12.7)

- **`PixelSyncApp.swift`** — rewrote the app entry point. `MenuBarExtra`
  (macOS 13+) and `.menuBarExtraStyle(.window)` replaced with an AppKit
  `NSStatusItem` + `NSPopover`. The app now uses
  `@NSApplicationDelegateAdaptor` and an empty `Settings` scene. Added:
  left-click toggles the popover, right-click shows a Quit menu, the menu-bar
  icon reflects connection state.
- **`ContentView.swift`** — `@StateObject private var bleManager` changed to an
  injected `@ObservedObject var bleManager` (single shared instance from
  `AppDelegate`). Two-parameter `.onChange(of:) { old, new }` (macOS **14**+)
  downgraded to the single-parameter form (macOS 11+). `cellularbars`
  `variableValue:` now guarded by `if #available(macOS 13.0, *)` with a
  fallback.
- **`Protocol.swift`** (new) — pure, hardware-free parser for the BLE wire
  protocol (`fields(from:)`, `parseTelemetry`, `parseNotification`,
  `parseCall`).
- **`BLEManager.swift`** — `didUpdateValueFor` refactored to use `PixelPacket`;
  added **call-event handling** (`handleCallEvent`) rendering
  incoming/answered/missed/ended call banners, with zh/en localisation.

### Android — Android 15 support + call events + dedup

- **`app/build.gradle.kts`** — `minSdk` lowered `36 → 35` (Android 15 minimum,
  Android 16 primary). `targetSdk`/`compileSdk` stay at 36.
- **`AndroidManifest.xml`** — added `READ_CONTACTS` (optional, caller names).
- **`MainActivity.kt`** — request `READ_CONTACTS` and `POST_NOTIFICATIONS`;
  seed default notification filter on first run.
- **`GattServerManager.kt`** — added `TelephonyCallback.CallStateListener`,
  a `ACTION_PHONE_STATE_CHANGED` receiver to capture the incoming number,
  `sendCallEvent(...)` (emits `CALL␟event␟number␟name`), and
  `resolveContactName(...)` via `ContactsContract.PhoneLookup`. Notifications
  are now byte-truncated to 180 B instead of char-truncated.
- **`MacSyncNotificationListener.kt`** — deterministic notification ids
  (`"n" + Integer.toHexString(sbn.key.hashCode())`) so re-sends / snapshots do
  not duplicate on the Mac; uses `NotificationFilter`.
- **`NotificationFilter.kt`** (new) — first-run default set of important apps
  (WeChat, QQ, SMS, Telegram, WhatsApp, Gmail …) filtered to installed ones.

### Build / tooling / docs

- **`MacOS/build_macos.sh`** (new) — Xcode-free build using `swiftc` targeting
  `x86_64-apple-macos12.0`; assembles the bundle, `Info.plist`, `.icns`,
  ad-hoc signature. Supports `ARCH=arm64|universal`.
- **`MacOS/run_tests.sh`** + **`MacOS/tests/parser_test.swift`** (new) —
  30 parser unit tests.
- Docs: `BLE_PROTOCOL.md`, `COMPATIBILITY.md`, `BUILD.md`, `INSTALL.md`,
  `TEST_REPORT.md`, `PROJECT_STATUS.md`, `CHANGELOG.md`.

## [real-device-validation] — 2026-09-30

Real-device validation performed with a test Android phone (Android 15 / API 35 /
HyperOS 3.0). Result: notification sync, screen-off delivery, Bluetooth OFF/ON
recovery and app-restart recovery all **PASS**; incoming-call and Mac sleep/wake
remain **NOT TESTED**. Full evidence in TEST_REPORT.md.

Changes made *only where a real-device test required it*:

- **`MacOS/PixelSync/PixelSync/BLEManager.swift` — notification delivery fallback.**
  *Original behaviour*: `UNUserNotificationCenter.add()` only.
  *Observed failure*: `requestAuthorization` returns `UNErrorDomain Code=1
  "Notifications are not allowed for this application"` on macOS 12.7.6 for any
  app not signed with an Apple-issued certificate (verified with a minimal app
  and a trusted self-signed identity; `spctl`-accepted too).
  *Change*: added `deliverNotification(...)` which uses UserNotifications when
  authorized and otherwise delivers to the native Notification Center via
  `/usr/bin/osascript`. `notificationsAuthorized` is set from the authorization
  callback. *Result*: real banner displayed (PASS). Native path remains primary
  if the app is later signed with an Apple certificate.
- **`MacOS/PixelSync/PixelSync/BLEManager.swift` — logging** switched from
  `print()` to `NSLog()` so logs are visible via `log show` regardless of launch
  method; added received-event logs (`POST`/`REMOVE`/call) and the
  authorization error text. (Maintainability; aids debugging.)
- **`MacOS/build_macos.sh` — stable code signing.** Uses the `PixelMacSync Dev`
  self-signed identity if present (created/trusted locally), else ad-hoc. A
  stable identity is required for Gatekeeper (`spctl`) acceptance; notifications
  still require an Apple certificate (see above).
- Documentation of the HyperOS quirks (install confirmation, notification-listener
  rebind) in INSTALL.md / TEST_REPORT.md.

No BLE protocol changes were made. `minSdk`/`targetSdk` were not changed during
this phase.

## [background-lifecycle + localization] — 2026-09-30

### Android lifecycle (background persistence)
- `MacSyncBleService`: `onStartCommand` returns **START_STICKY**; added
  **`onTaskRemoved`** (re-assert foreground; keeps BLE/notification sync alive
  after Recents swipe); idempotent `ensureBleRunning()`; `setOngoing(true)` FGS
  notification; manifest `android:stopWithTask="false"`.
- `MacSyncNotificationListener`: added **`onListenerConnected()`** /
  **`onListenerDisconnected()`** with **`requestRebind(ComponentName)`** to
  self-heal the HyperOS "enabled ≠ live" state.
- `BootReceiver`: handles **`MY_PACKAGE_REPLACED`** in addition to
  `BOOT_COMPLETED`, with try/catch + real-outcome logging (Android 15 FGS rules).
- Audited: no `killProcess`/`System.exit`; Activity does not stop the service
  (already decoupled); telephony callbacks live in the singleton, not the Activity.

### Android localization (zh-rCN / en)
- Added `res/values/strings.xml` (English default) and
  `res/values-zh-rCN/strings.xml` (Simplified Chinese).
- `MainActivity`, `AppSelectionScreen`, `MacSyncBleService`, `GattServerManager`
  now use string resources; `GattServerManager` exposes a typed `connected`
  `StateFlow`; added a battery-optimization warning card (opens system settings).

### macOS localization (zh / en)
- New `Localization.swift` (`L10n`) with English/Chinese selection.
- `BLEManager` connection status refactored from Italian string-matching to a
  typed `connectionState` enum + `isConnected`; `ContentView` / `PixelSyncApp`
  localize labels, tooltips and the Quit menu. macOS 12 compatibility kept.

### Automation safety infra
- `scripts/warning.sh` — sound + macOS notification + terminal alert + writes
  `HUMAN_INTERVENTION_REQUIRED` and appends `AUTOMATION_STATE.md`.
- `Android/root/pixelsync_watchdog.sh` — SukiSU `/data/adb/service.d/` watchdog:
  checks process/FGS/listener/BT, graded recovery, exponential backoff
  (5→120 s), 60 s healthy poll, single-instance lock, rotates log, and
  **does not fight Force Stop**. Not yet installed (root not available via ADB).

### Root watchdog (installed, running)
- Installed to `/data/adb/service.d/pixelsync_watchdog.sh` (SukiSU Ultra,
  `ksud 4.2.0`). Runs at boot; graded recovery + backoff; Force-Stop aware;
  single-instance lock; log rotation.
- **Bug fixed during validation:** `pkg_force_stopped` matched the always-stopped
  **User 999** line from `dumpsys package`, producing a false "Force Stop"
  verdict that blocked recovery. Now checks only the `User 0:` line.

### Real-device results this session (all real)
- Chinese Android UI **PASS** (已连接 / 设置 / 通知 / 与 Mac 同步通知).
- **Home PASS**, **Recents Swipe PASS**, **Swipe + BLE PASS**,
  **Swipe + Notification PASS**, **Swipe + Screen-Off PASS**,
  **Swipe + Reconnect PASS**.
- **Process death (SIGKILL) → watchdog recovery PASS**
  (`process missing → start FGS → process revived → listener rebound → state OK`).
- **NotificationListener rebind PASS** (HyperOS enabled≠live handled).
- **Boot recovery PASS**: after a real reboot the service.d watchdog revived the
  app + listener; the Mac auto-reconnected; a **real WeChat notification**
  synced (`POST ricevuto ... pkg=com.tencent.mm`).
- **Force Stop = LIMITATION** (not bypassed).
- Regression BLE / Notification / Doze / Reconnect **PASS**.
- 2 h / 8 h long-run: **NOT TESTED**.
- Safety gate fired twice on post-reboot ADB disconnects (marker written, then
  cleared once ADB recovered).

## [remote hotspot + login autostart] — 2026-09-30

### Remote hotspot (Mac → BLE → Android → Root → HyperOS)
- **Android** `HotspotController.kt` (new): real hotspot via root
  `cmd wifi start-softap` / `stop-softap` (the only stable surface on Android 15 /
  HyperOS 3). Reuses the saved SoftAP config via reflection; never logs/transmits
  the passphrase. Command channel now handles `HOTSPOT_ENABLE`/`HOTSPOT_DISABLE`/
  `HOTSPOT_STATUS`; replies `HOTSPOT_STATE␟ON/OFF` or `HOTSPOT_ERROR␟code` after
  waiting for the real `WIFI_AP_STATE_CHANGED`.
- **macOS** `BLEManager`: typed `HotspotState`, `setRemoteHotspot`/`queryHotspotState`,
  real-state handling + 12 s timeout; `Protocol.swift` parses hotspot replies.
  `ContentView`: the existing "远程热点" entry now shows real state and drives it
  (no optimistic ON). `HOTSPOT_STATUS` is queried on reconnect.
- **URL-scheme hook** (`pixelsync://hotspot/on|off|status`, AppDelegate
  `application(_:open:)`) for deterministic/automation control.
- No credentials or protocol tokens are translated. See HOTSPOT.md.

### macOS login autostart
- **`LoginItem.swift`** (new): per-user LaunchAgent
  `~/Library/LaunchAgents/it.luigi.pixelsyncmac.plist` (`RunAtLoad`, Aqua session);
  `launchctl load/unload`; no sudo/system dirs/third-party helpers.
- Popover gained a "开机自动启动" toggle (real plist write/remove). Optional URL
  hook `pixelsync://login/on|off`.

### Real-device results
- Remote Hotspot **Enable PASS**, **Disable PASS**, **State Sync PASS**
  (verified real `wlan2` interface up/down; Mac shows ON→OFF).
- macOS Login Auto Start **PASS** (LaunchAgent loaded; launchd started the app via
  RunAtLoad). Logout/login test pending.
- **LIMITATION**: `start-softap` does not invoke Tethering NAT (internet sharing).
- Observed once: a BLE drop after enabling the 2.4 GHz hotspot (recovered via BT
  toggle); cause ambiguous (coexistence vs the device's recurring BT wedge).

### Known gaps / not implemented

- No explicit `message_id` / `sequence_number` / ACK layer. Dedup is by stable
  id (sufficient for current event volume). Documented in BLE_PROTOCOL.md.
- Caller **number** is best-effort (Android 9+ restrictions); no `READ_CALL_LOG`.
- Reconnect-on-macOS-12-sleep/wake and full real-device matrix **not tested**
  (no phone available).
- Android APK **was** built and signed successfully (debug + release/R8);
  see TEST_REPORT.md A10–A13.
