# HOTSPOT.md — Remote hotspot control (Mac → Android → Root → HyperOS)

## Goal

From the macOS menu bar, the user toggles the phone's Wi-Fi hotspot. The command
travels over the **existing BLE command channel**; Android applies it via **root**
and reports the **real** system state back. The Mac never assumes success.

```
Mac PixelSync  --BLE-->  Android MacSync  --root-->  cmd wifi start/stop-softap  --HyperOS-->  real SoftAP
        ^                                                                                                 |
        +-------------------------- HOTSPOT_STATE|ON / OFF / ERROR  (BLE) <-------------------------------+
```

## Why not the TetheringManager

Device audit (POCO F5 Pro, Android 15, HyperOS 3, `ksud 4.2.0`):

| Surface | Result |
|---|---|
| `cmd tethering` | **No shell command implementation** |
| `cmd connectivity` | airplane-mode / firewall only — no tether |
| `service call tethering` | AIDL needs a binder `IIntResultListener` — not callable from a shell |
| `svc` | power / usb / nfc / system-server only |
| `cmd statusbar click-tile` | only `TileService` components, not the built-in hotspot tile |
| **`cmd wifi start-softap <ssid> <sec> <pass>` / `cmd wifi stop-softap`** | ✅ stable, root-accessible, real SoftAP |

`cmd wifi start-softap` starts a **real** SoftAP (verified: `SoftApState mState=13
ENABLED`, iface `wlan2`) and it does **not** overwrite the device's saved hotspot
configuration (verified: saved SSID unchanged after start/stop).

## Credentials & SSID stability

- The app tries `WifiManager.getSoftApConfiguration()` first. On this device it
  throws `SecurityException: App not allowed to read or update stored WiFi Ap
  config` (privileged permission), so the **passphrase cannot be read**.
- **SSID**: the app reuses the system's saved AP SSID via root
  (`dumpsys wifi | grep CMD_UPDATE_AP_CONFIG`), so the network name stays
  **"POCO F5 Pro"** (stable) and matches what the phone normally broadcasts.
  This fixes the earlier "sometimes visible / sometimes not" symptom caused by
  the old fallback SSID `MacSync Hotspot`.
- **Passphrase**: app-private, generated once, stored in `SharedPreferences`; it
  is **never** logged, never sent over BLE, never written to a report. The
  Android app **displays it** ("需在 Mac 保存的热点 / 网络名称 / 密码") so the user
  can save this Wi-Fi on macOS **once**; after that it auto-joins.
- Cleanup note: an earlier test created a stray `MacSync Hotspot` config in the
  phone's saved hotspot list; remove it in Settings if desired.

## Real-state confirmation (no optimistic UI)

1. Mac sends `HOTSPOT_ENABLE` / `HOTSPOT_DISABLE`.
2. Mac UI enters `enabling` / `disabling` (not `on`/`off`).
3. Android runs the root command.
4. Android waits for the system **`WIFI_AP_STATE_CHANGED`** broadcast (real state)
   to match, up to ~7 s.
5. Android sends `HOTSPOT_STATE␟ON`/`OFF`, or `HOTSPOT_ERROR␟…`.
6. Mac shows the real state. A 12 s timeout on the Mac turns a missing reply into
   `error`.

The real state is also pushed continuously in the telemetry field
`isHotspotActive`, so changing the hotspot **on the phone** updates the Mac.

## State machine (Mac)

```
OFF --enable--> ENABLING --confirm--> ON
                  |  (timeout / HOTSPOT_ERROR)
                  v
                ERROR --> (next telemetry) OFF/ON
ON --disable--> DISABLING --confirm--> OFF
```

When BLE is disconnected the control is disabled and the state is `unknown`
until the next status query on reconnect (`HOTSPOT_STATUS`).

## Limitations (honest)

- `cmd wifi start-softap` raises the AP. **Internet NAT is governed by the
  Tethering framework**, which this shell surface does not invoke. On this device
  the AP is real and verifiable; client internet depends on the device's
  bridged-AP-with-STA support (e.g. when the phone is itself on Wi-Fi). This is a
  **HyperOS/Android-15 specific limitation** of the root shell route.
- `HOTSPOT_ENABLE`/`DISABLE`/`STATUS` and the tokens above are **machine protocol
  fields** and are never translated.
- Requires root granted to `it.luigi.macsync` in SukiSU (verified present).
- Applies to Android 15 / HyperOS 3 on this device; other ROMs may differ.

## Test status

See TEST_REPORT.md (section R). Enable/Disable end-to-end still requires the
user to grant macOS Accessibility (for UI automation) or click the menu once.
