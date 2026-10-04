# BLE_PROTOCOL.md — PixelMacSync wire protocol

This document describes the **actual** GATT protocol used by PixelMacSync, as
implemented in `Android/.../ble/GattServerManager.kt`, `.../BLEAdvertiser.kt` and
`MacOS/.../BLEManager.swift`, plus the **call-event extension** added by the
macOS 12.7 port.

The protocol is **unchanged in spirit** from upstream: it is a compact,
separator-based (not JSON) byte protocol over GATT notifications. It is
event-driven — data is sent only when something changes.

## Roles

| Device | Role | Implementation |
|---|---|---|
| Android (test Android phone) | Peripheral / GATT Server | `BluetoothGattServer` + `BluetoothLeAdvertiser` |
| macOS (MacBook Pro 2015) | Central / GATT Client | `CBCentralManager` / `CBPeripheral` |

No TCP, no Wi-Fi, no cloud. The link is **BLE GATT only**.

## UUIDs

> Upstream README asks you to replace these with your own values before building
> (to avoid two people in the same room crossing streams). The port keeps the
> upstream defaults so the existing Android/Mac binaries match.

| Purpose | UUID | Properties |
|---|---|---|
| Primary Service | `58DF214B-9942-45A5-BAF9-7B24F5D0232C` | PRIMARY |
| Telemetry | `CEFB6548-6C8A-4D25-A086-C8A69D3F6625` | READ, NOTIFY |
| Notifications | `6E6C9609-9FFA-42E2-A882-B0C4398D58DE` | NOTIFY |
| Commands | `586B06E6-CCC5-44B8-BFD9-5D2514A67842` | WRITE |

CCC descriptor UUID: standard `00002902-0000-1000-8000-00805f9b34fb`.

MTU: the protocol keeps every payload **≤ 180 bytes** (safe under the default
BLE 23-byte ATT MTU after negotiation on the Pixel's larger MTU). Long strings
are truncated. JSON is intentionally **not** used.

Separator: `US` (Unit Separator, `0x1F`, Swift/Kotlin `\u001F`).

## Connection / discovery

1. Android starts a **connectable** BLE advertisement containing the primary
   Service UUID (`ADVERTISE_MODE_LOW_LATENCY`, `TX_POWER_HIGH`). Device name is
   not advertised.
2. Mac scans `scanForPeripherals(withServices: [serviceUUID])`.
3. Mac first tries `retrieveConnectedPeripherals(withServices:)` (system cache,
   survives app relaunch), then falls back to scanning.
4. Mac connects, discovers the service + the three characteristics, subscribes
   (`setNotifyValue(true)`) on Telemetry and Notifications, and on finding the
   Command characteristic sends `HELLO US <macId>` (session handshake) followed
   by `SYNC_REQ` (cold sync).
5. Android validates the handshake and answers `SESSION_READY US <phoneName> US <macId>`;
   only then is the application session considered established (BUG-001 fix).
6. Android `GattServerManager` answers status changes and pushes telemetry.

## Session handshake (BUG-001)

A BLE/GATT link alone is **not** a session. Android shows "Connected" only after
**all** hold:

1. the GATT link is connected,
2. the Mac subscribed to the **Notifications** characteristic (CCC write),
3. a real Mac identity/activity is present: either an explicit `HELLO US <macId>`
   (new macOS builds) **or** any command written on the Command channel
   (`SYNC_REQ`, `MUSIC_*`, `HOTSPOT_*`, `KILL`, …). The latter keeps the legacy
   v2.2 macOS client (which sends `SYNC_REQ`/`MUSIC_*` but no `HELLO`) working.

Until then the Android UI shows **"Verifying…"**. A link that never completes the
handshake is cancelled after ~10 s and the normal reconnect path retries. On
disconnect the session resets immediately (prompt return to "Disconnected").

- `macId` is a **stable** per-Mac identifier derived from the host UUID, hashed to
  a short hex string (`Mac-XXXXXXXXXXXXXXXX`); it is deterministic, so reinstalling
  or clearing preferences never registers the same Mac twice. The raw hardware UUID
  is never transmitted.
- Android confirms with `SESSION_READY US <phoneName> US <macId>` on the
  Notifications channel (`<macId>` is `legacy` when the peer only sent commands).
  The Mac shows "Connected" only on receiving it.
- Backward compatibility: an old macOS build (no `HELLO`) still establishes a
  session as soon as it subscribes and sends a command (e.g. `SYNC_REQ`). A stray
  BLE client that never subscribes/sends commands never becomes "Connected".

## Multi-Mac (Phase 1)

Each identified Mac (one that sends `HELLO US <macId> [US <name>]`) is saved on the
phone. Exactly one Mac is **active**. Android accepts a session only from the active
Mac; a different identified Mac is refused:

```
SESSION_READY    US <phoneName> US <macId>     (active Mac accepted)
SESSION_REJECTED US not_active | user_disconnected
```

- A Mac takes over as active automatically if none (or only the legacy `HELLO`-less
  client) is active; otherwise it must be selected on the phone.
- **User disconnect** (`user_disconnected`) means the user removed the session on the
  phone; that Mac must not auto-reconnect until re-selected.
- The Mac, on `SESSION_REJECTED`, stops auto-rescanning and shows "非当前 Mac 设备";
  it retries every ~30 s so it reconnects once made active.
- Legacy clients (no `HELLO`) are accepted (no active enforcement) but are **not**
  added to the saved list: they cannot be told apart and would otherwise show up as a
  duplicate "Legacy Mac" next to the same computer. Use an updated macOS build
  (which sends `HELLO US <macId> [US <name>]`) for Multi-Mac.

See `MacRegistry.kt` (phone) and `BLEManager` (Mac).

## Telemetry channel (Notify / Read)

UTF-8, `US`-separated, 7 fields:

```
<battery%> US <isCharging:true|false> US <network|SSID> US <signal 0..4> US <isWifi:true|false> US <isHotspotActive:true|false> US <Build.MODEL>
```

Example: `87\x1Ftrue\x1FMyWiFi\x1F3\x1Ftrue\x1Ffalse\x1FTest Phone`

Mac parses it in `didUpdateValueFor` (`telemetryUUID` branch) and updates the
menu-bar popover.

## Notification channel (Notify)

Two actions, each a single `US`-separated packet:

```
POST   US <id> US <packageName> US <title> US <body> US <appLabel> [US <replyable>]
REMOVE US <id>
```

- `id` is a **deterministic** value derived from the Android notification key:
  `"n" + Integer.toHexString(sbn.key.hashCode())`.
  This makes re-sends / reconnects idempotent: the Mac uses the id as the
  `UNNotificationRequest` identifier, so the same notification **replaces**
  rather than duplicates.
- The Mac also applies its own dedup naturally (same identifier = same banner).
- Android filters out group summaries and system packages (`android`,
  `com.android.systemui`) and only forwards packages in the user's enabled set.
- `appLabel` (6th field) is the sender app's display name (see *Sender app name*).
- `replyable` (7th field, optional) is `1` when the notification exposes a
  free-form `RemoteInput` reply action, else `0`. The Mac shows the inline
  **Reply** field only for `1`, so the user can tell which notifications can be
  answered. Older 6-field payloads default to `0`.

## Command channel (Write from Mac)

```
HELLO US <macId> [US <name>]
HOTSPOT_ENABLE
HOTSPOT_DISABLE
HOTSPOT_STATUS
SYNC_REQ
KILL US <id>
MUSIC_PLAY
MUSIC_PAUSE
MUSIC_NEXT
MUSIC_PREV
MUSIC_SEEK US <positionMs>
MUSIC_STATUS
REPLY US <id> US <base64(text)>
CALL_ANSWER
CALL_END
CALL_REJECT          (alias of CALL_END)
CALL_MUTE            (toggle)
DIAL US <number>
CONTACT_SYNC
```

`HOTSPOT_ON` / `HOTSPOT_OFF` are accepted as aliases of `HOTSPOT_ENABLE` /
`HOTSPOT_DISABLE` (the upstream MacroDroid path is replaced by a real root
control — see HOTSPOT.md).

### Inline reply (Mac → Android → notified app)

`REPLY US <id> US <base64(text)>` asks Android to answer the origin
notification inline. Android finds the still-active `StatusBarNotification`
whose stable id matches, picks its action that exposes free-form `RemoteInput`,
and injects the text via
`RemoteInput.addResultsToIntent(...)` + `action.actionIntent.send(...)`.
base64 keeps the separator/UTF-8 safe. The reply is truncated on the Mac so the
whole BLE write fits `maximumWriteValueLength(for: .withResponse)`.

Outcome (Android → Mac, on the Notifications channel):

```
REPLY_RESULT US <id> US ok
REPLY_RESULT US <id> US not_found
REPLY_RESULT US <id> US no_reply_action
REPLY_RESULT US <id> US error
```

`no_reply_action` is the expected **LIMITATION** for apps that only offer
"mark as read"/dismiss (no free-form reply). Whether a banner even shows the
reply field is driven by the `replyable` flag of its `POST` (see above), so the
user can tell at a glance which notifications can be answered. The macOS inline
reply UI itself requires the app to show a native `UserNotifications` banner,
i.e. an Apple-issued signature; with the ad-hoc release build notifications fall
back to `osascript`, which cannot render custom actions.

### Hotspot replies (Android → Mac, on the Notifications channel)

```
HOTSPOT_STATE  US ON
HOTSPOT_STATE  US OFF
HOTSPOT_RESULT US OK | ALREADY_ON | ALREADY_OFF
HOTSPOT_ERROR  US <safe_code>
```

- `HOTSPOT_RESULT` reports the idempotent outcome (BUG-002): enabling when the AP
  is already on yields **`ALREADY_ON`** and disabling when it is already off yields
  **`ALREADY_OFF`** — both are **successes**, never errors. `OK` means the real
  state changed as requested.
- `HOTSPOT_STATE` always carries the **real** state; the Mac also receives it
  continuously via the telemetry field `isHotspotActive`.
- `safe_code` ∈ `{ enable_failed, disable_failed }` (the old `state_mismatch` is
  no longer emitted; the Android side now decides from the real system state).
- Android determines the real state from `dumpsys tethering` (an active
  `wlanX - …Tether` interface), **not** from the `start/stop-softap` stdout, which
  is unreliable when the AP is already on (it prints both "enabled successfully"
  and "failed to start").
- No hotspot password is ever sent over BLE or written to any log.

### App icon transfer (Android → Mac, one-time per app)

Sent on the Notifications channel, base64-chunked inside the US protocol so it
fits the BLE budget (each packet ≤ 176 chars). Chinese/emoji are not involved.

```
ICON_BEGIN US <package>
ICON_DATA  US <package> US <seq> US <base64 chunk>
ICON_END   US <package>
```

The Mac reassembles (ordered by `seq`) and caches
`~/Pictures/MacSyncIcons/<package>.png`. This works **without** the Mac having
the app installed. Android sends an icon once per app per process. Displaying it
requires a notification API that supports a content image: currently
`UserNotifications` is refused on macOS 12 for non-Apple-signed apps and
`osascript` cannot set icons, so the cached icon is shown once the Mac app is
signed with an Apple certificate.

### Sender app name

`POST` carries a 6th field with the sender app's display name; the Mac shows it
as the notification header (title), with the original title as subtitle. This is
backward compatible (older payloads simply omit the field).

- `HOTSPOT_ON/OFF` → broadcast to MacroDroid (optional third-party integration).
- `SYNC_REQ` → Android sends a snapshot of all currently active notifications as
  `POST` packets.
- `KILL US <id>` → Android cancels the originating notification (reverse dismiss
  when the user clicks the Mac banner). Mac also handles this by looking the
  Android package up in `Documents/MacSync/app_mappings.json`.

### Click actions (Mac side)

There is **no app picker on notifications** (it used to look like Finder). A
banner shows only the **Reply** field, and only when the origin notification is
`replyable=1`. Targets come from `Documents/MacSync/app_mappings.json` (Android
package → macOS app name, opened via `open -a`); URL/webpage mappings were
removed. To add a mapping, edit that JSON (or the app's defaults).

- **Plain banner click**: opens the mapped app if known, otherwise does nothing.
  It **never** deletes the phone notification and never opens a Finder-like panel.
- **Dismiss in Notification Center** (swipe / clear): sends `KILL US <id>`, the
  only path that clears the originating phone notification.
- **Reply**: sends `REPLY` and leaves the phone notification in place.

Missing default entries are merged into an existing `app_mappings.json` on load.

## Call-event extension (NEW in this port)

Added because upstream had **no** telephony handling at all.

Sent on the **Notifications** characteristic (same channel as notifications),
with a new leading token:

```
CALL US <event> US <number> US <name>
```

`event` ∈ `{ RINGING, OFFHOOK, IDLE, MISSED }`.

| Android event | Origin | Mac rendering |
|---|---|---|
| `RINGING` | `CALL_STATE_RINGING` | banner “来电 / Incoming call”, subtitle = contact, body = number, sound |
| `OFFHOOK` | `CALL_STATE_OFFHOOK` | removes incoming banner, posts “通话中 / Call answered” (silent) |
| `IDLE` | `CALL_STATE_IDLE` (no ring seen) | removes call banners |
| `MISSED` | `RINGING → IDLE` without `OFFHOOK` | removes incoming banner, posts “未接来电 / Missed call” |

- The incoming number is best-effort: captured from the system
  `ACTION_PHONE_STATE_CHANGED` broadcast (`EXTRA_INCOMING_NUMBER`). On Android 9+
  this may be empty unless the app is privileged; the Mac then shows
  “未知号码 / Unknown number”.
- The contact name is resolved via `ContactsContract.PhoneLookup` **only if**
  `READ_CONTACTS` is granted; otherwise the name field is empty.
- **No call audio / no HFP** (see ARCHITECTURE target). Event delivery **and**
  BLE call control (below) only.

### Call control (Mac → Android → telephony)

```
CALL_ANSWER                  answer the ringing call
CALL_END / CALL_REJECT       end / reject the call
CALL_MUTE                    toggle microphone mute
```

Android executes these with a **privileged `input keyevent`** (root or Shizuku),
because Android 15 has no shell `telecom` accept/end/mute and `TelecomManager`
accept/end are restricted for non-default dialers:

| Command | Keyevent |
|---|---|
| answer | `KEYCODE_CALL` (5) |
| end | `KEYCODE_ENDCALL` (6) |
| mute | `KEYCODE_MUTE` (91), best-effort |

Outcome (Android → Mac, on the Notifications channel):

```
CALL_RESULT US answer|end|mute US ok|failed
CALL_MUTE_STATE US ON|OFF        (after a mute toggle)
```

The Mac shows **Answer / Reject** while ringing and **Mute / Hang up** while
active (popover), and reports a failure banner if `CALL_RESULT` is `failed`.

> DEBUG builds only: a simulated call can be injected without telephony via
> `CallSimReceiver` (see `scripts/sim-call.sh`) to test the whole flow with no
> second phone. Never present in release.

## Contacts + remote dialing (NEW)

### Contacts (Android → Mac, encrypted at rest on the Mac)

Only the contacts the user **selects** are sent (default: none). The Mac stores
them encrypted (AES-GCM, key in the Keychain) — never plaintext.

```
CONTACT_BEGIN US <count>
CONTACT       US <id> US <name> US <number>     (repeated)
CONTACT_END
CONTACT_DEL   US <id>                            (removed on the phone)
```

- Android sends the selected contacts automatically on connect when the
  "auto-sync on connect" option is on, and always on a `CONTACT_SYNC` request.
- Names are truncated to fit the BLE budget; numbers are never logged.
- The Mac keeps them in `~/Library/Application Support/PixelSync/contacts.enc`.

### Remote dialing (Mac → phone)

```
DIAL        US <number>
DIAL_RESULT US ok | failed | invalid
```

- **No real call is ever placed.** The number is validated on Android
  (`^[+*#0-9]{1,20}$`) to prevent shell injection, then the phone opens the
  **system dialer** prefilled (`ACTION_DIAL`) — a *simulated dial*; the user still
  presses call. With root/Shizuku it is brought up via a privileged `am start`
  (background-safe). `ACTION_CALL` is deliberately **never** used.
- Contacts stay on the phone; the Mac's dial field suggests matches by number
  prefix / name from its own encrypted store.
- **Call audio**: "phone call" is fully supported. "Mac Bluetooth call" (HFP
  audio) is **not available** on this Mac (no public HFP-HF path) and is shown
  greyed in the UI.

## Music-control extension (NEW in this port)

Phone now-playing metadata is displayed on the Mac and playback is controllable
from the Mac. It is **event-driven** (active-session / metadata / playback
callbacks) — no polling. Android obtains the sessions through its already
enabled `NotificationListenerService` identity
(`MediaSessionManager.getActiveSessions(ComponentName)`), so **no new
permission** is required.

### Android → Mac (Notifications channel)

```
MUSIC_META US <title> US <artist> US <album> US <durationMs> US <positionMs> US <state> [US <coverKey>]
```

`state` ∈ `{ playing, paused, stopped }`. The optional 8th `coverKey` (empty if
none / omitted by older senders) identifies which cover belongs to the current
track: the Mac shows that key's cached cover immediately and **ignores any
`ART_END` whose key is not the announced one**, so fast track skipping or
out-of-order art can never display a stale cover. Sent only when the metadata
or playback state changes (and once on `MUSIC_STATUS`). The Mac extrinsically
advances the progress clock locally as
`position + (now − receivedAt)` while playing, so no progress stream is
needed.

Cover art is sent once per track, exactly like app icons (US-safe base64,
single-threaded, paced at 18 ms/packet, JPEG ≤ 256 px q82):

```
ART_BEGIN US <key>
ART_DATA  US <key> US <seq> US <base64 chunk>
ART_END   US <key>
```

`key` = `<package>-<hex(trackHash)>`; the Mac reassembles and caches it at
`~/Pictures/MacSyncCovers/<key>.jpg`.

### Media volume (Android → Mac / Mac → Android)

```
MUSIC_VOLUME      US <percent 0..100>   (Android → Mac, on change)
MUSIC_VOLUME_SET  US <percent 0..100>   (Mac → Android)
```

Android reports the `STREAM_MUSIC` volume percentage over the Notifications
channel and observes `Settings.System` (`volume_music`) so the Mac slider also
tracks the phone's hardware volume keys. Setting the volume uses
`AudioManager.setStreamVolume` (no permission); the resulting observer callback
echoes the real value back, so the Mac is never optimistic for long.

### Mac → Android (Command channel)

`MUSIC_PLAY`, `MUSIC_PAUSE`, `MUSIC_NEXT`, `MUSIC_PREV`,
`MUSIC_SEEK US <ms>`, `MUSIC_STATUS` (re-send metadata + cover + volume, used
after a reconnect), `MUSIC_VOLUME_SET US <percent>`.

### Limitations

- **Lyrics**: Android exposes no standard lyrics API and `MediaMetadata` has no
  lyrics field; because the project forbids network access, lyrics are shown
  only if a player puts them in the session extras. Marked **LIMITATION**.
- **Player coverage**: some apps do not expose a `MediaSession` or cover art;
  the Mac then falls back to text-only (or no track).

## Reliability / dedup notes

- `notifyCharacteristicChanged(..., confirm=false)` = ATT notifications (no
  ATT-level ACK). The BLE link layer still retransmits while connected.
- Dedup is achieved with **stable ids**, so the missing ACK does not create
  duplicates on the Mac.
- Snapshot on reconnect re-sends `POST` with the same email-stable ids; the Mac
  clears delivered notifications on reconnect and re-adds them, so state is
  re-synchronised rather than duplicated.
- A full `message_id` / `sequence_number` / explicit ACK scheme is **not**
  implemented (documented as future work in CHANGELOG).

## Connection parameters (battery-first)

The app relies on Android's default GATT connection parameters for a connected
peripheral, which are balanced for a phone peripheral. If tuning is needed:

- **Connection interval**: 30–50 ms (do **not** go to 7.5 ms — that is for
  latency-critical HID, not notifications).
- **Slave latency**: 4–6 (lets the phone skip intervals when idle).
- **Supervision timeout**: 4–6 s (must be > (1 + latency) × interval).

These values are chosen so the phone can stay connected for hours without a
meaningful battery hit. The app never polls; it only sends on real events.

## Reconnect behaviour

- Android advertises whenever the GATT server runs; `MacSyncBleService` is a
  **Foreground Service** and restarts advertising when Bluetooth is toggled on
  (2 s delay for the controller).
- Mac reconnects automatically: `didDisconnectPeripheral` → wait 2 s → rescan /
  `retrieveConnectedPeripherals`; a 20 s watchdog triggers a clean
  re-instantiation of `CBCentralManager` (`forceRestartBluetooth`).
- Mac handles sleep/wake via `NSWorkspace.willSleepNotification` /
  `didWakeNotification`.
- On reconnect the Mac sends `SYNC_REQ` and the phone replies with a snapshot.
