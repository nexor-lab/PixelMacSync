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
| Android (POCO F5 Pro) | Peripheral / GATT Server | `BluetoothGattServer` + `BluetoothLeAdvertiser` |
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
   Command characteristic sends `SYNC_REQ` (cold sync).
5. Android `GattServerManager` answers status changes and pushes telemetry.

## Telemetry channel (Notify / Read)

UTF-8, `US`-separated, 7 fields:

```
<battery%> US <isCharging:true|false> US <network|SSID> US <signal 0..4> US <isWifi:true|false> US <isHotspotActive:true|false> US <Build.MODEL>
```

Example: `87\x1Ftrue\x1FMyWiFi\x1F3\x1Ftrue\x1Ffalse\x1FPoco F5 Pro`

Mac parses it in `didUpdateValueFor` (`telemetryUUID` branch) and updates the
menu-bar popover.

## Notification channel (Notify)

Two actions, each a single `US`-separated packet:

```
POST   US <id> US <packageName> US <title> US <body> US <appLabel> [US <url>]
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
- `url` (7th field, optional) is a best-effort `http(s)` deep link scraped from
  the notification extras. The real tap target is an opaque `PendingIntent` that
  cannot be serialised, so most apps leave this empty. When present, the Mac
  opens it on click (highest priority).

## Command channel (Write from Mac)

```
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
```

`HOTSPOT_ON` / `HOTSPOT_OFF` are accepted as aliases of `HOTSPOT_ENABLE` /
`HOTSPOT_DISABLE` (the upstream MacroDroid path is replaced by a real root
control — see HOTSPOT.md).

### Hotspot replies (Android → Mac, on the Notifications channel)

```
HOTSPOT_STATE US ON
HOTSPOT_STATE US OFF
HOTSPOT_ERROR US <safe_code>
```

`safe_code` ∈ `{ enable_failed, disable_failed, state_mismatch }` (never a
credential). The Mac also receives the real hotspot state continuously via the
telemetry field `isHotspotActive`. No hotspot password is ever sent over BLE or
written to any log.

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

### Click-to-open (Mac side)

When the user clicks a banner, the Mac resolves the target in this order:

1. the optional 7th `url` field of the `POST` (e.g. a scraped tweet permalink);
2. the user mapping in `Documents/MacSync/app_mappings.json`, whose value may be
   a macOS app name (`"Instagram"`, opened via `open -a`) **or** an `http(s)`
   URL (`"com.twitter.android": "https://x.com/notifications"`, opened in the
   default browser);
3. otherwise it shows an `NSOpenPanel` to pick a `.app`, and saves the choice.

The Mac also sends `KILL US <id>` so the phone-side notification is cleared.

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
- **No call audio, no HFP, no answering.** Event notification only.

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
