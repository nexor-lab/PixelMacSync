# INSTALL.md — installing PixelMacSync

> The final communication path is **BLE GATT only**:
> `test Android phone ⇄ (Bluetooth LE) ⇄ MacBook Pro 2015`.
> USB / ADB / Wi-Fi / hotspot / LAN / Internet are **not** used at runtime.

---

## macOS — PixelSync.app

1. Build (see BUILD.md) or use the provided bundle, then copy it:
   ```sh
   ditto MacOS/build/PixelSync.app /Applications/PixelSync.app
   ```
2. Launch it:
   ```sh
   open /Applications/PixelSync.app
   ```
   It is a **menu-bar agent** (no Dock icon). Look for the antenna/iPhone icon
   in the menu bar.
3. On first use macOS asks to allow **Bluetooth** — accept.
   If not asked: System Settings → Privacy & Security → Bluetooth → enable
   *PixelSync*.
4. Allow **Notifications** for PixelSync when prompted (System Settings →
   Notifications → PixelSync).
5. Click the menu-bar icon → the popover shows connection status / telemetry.
6. *(Optional)* App↔package mapping is stored in
   `~/Documents/MacSync/app_mappings.json` and can be edited by hand.

### Gatekeeper

The app is **ad-hoc signed** (not notarized). If macOS blocks it:

```sh
xattr -dr com.apple.quarantine /Applications/PixelSync.app
```

---

## Android — MacSync APK

1. Install the APK:
   ```sh
   adb install -r MacSync.apk
   ```
   (Over USB/ADB is only for installation/debugging; it is not the sync link.)
2. Open **MacSync**.
3. Grant the requested permissions:
   - **Bluetooth** — Nearby devices (`BLUETOOTH_CONNECT`,
     `BLUETOOTH_ADVERTISE`).
   - **Phone state** (`READ_PHONE_STATE`) — for call events.
   - **Contacts** (`READ_CONTACTS`) — *optional*, to show caller names.
   - **Notifications** (`POST_NOTIFICATIONS`) — for the foreground service
     notification.
   - **Location** — needed by Android to read the Wi-Fi SSID shown in telemetry;
     you can deny it and the app falls back to the literal string `"Wi-Fi"`.
4. Grant **Notification access**:
   the app shows a warning card “Accesso alle Notifiche”; tap it and enable
   *MacSync* in **Settings → Notifications → Device & app notifications** (path
   varies by OEM).
5. **Disable battery optimisation** for MacSync:
   Settings → Apps → MacSync → Battery → **Unrestricted**.
   Xiaomi/POCO (MIUI/HyperOS) additionally: Settings → Apps → MacSync →
   **Autostart ON**, and Battery saver → **No restrictions**.
6. Tap the **Notifications** card to choose which apps are forwarded. On first
   run a sensible default set (WeChat, QQ, SMS, Telegram, WhatsApp, Gmail) is
   pre-selected **for the ones installed**.
7. Keep MacSync running. The foreground service ("MacSync Attivo") keeps BLE
   advertising alive; it restarts on boot (`BootReceiver`).

---

## Pairing / first connection

1. Put both devices within a few metres.
2. On the Mac, PixelSync scans for the service UUID automatically.
3. When found it connects, subscribes, and sends `SYNC_REQ`. The Android app
   shows **“Connesso al Mac!”**.
4. No manual “Connect” button is required afterwards — proximity + automatic
   reconnect handle it.

---

## HyperOS / MIUI notes (test Android phone, verified)

- **Installing via ADB**: HyperOS shows a **"USB安装提示"** dialog per install
  ("正在通过USB安装此应用，是否继续？"). It auto-rejects after a countdown, so tap
  **继续安装** to proceed. (If blocked: Developer options → *Install via USB*.)
- **Notification access**: after granting Notification access, the listener may
  be listed as enabled but **not bound** by the system. Toggling Notification
  access **off and on** (or `cmd notification disallow_listener` then
  `allow_listener`) forces it to bind. Verified on HyperOS 3.0.
- **Background**: keep the app's battery restriction **Unrestricted** and enable
  **Autostart** so the foreground service survives.

## macOS notification permission note

On macOS 12.7, `UserNotifications` refuses apps that are **not signed with an
Apple certificate** (`Notifications are not allowed for this application`).
PixelSync detects this and automatically falls back to delivering to the native
Notification Center via `osascript`, so banners still appear. To get fully
native, correctly-attributed notifications, sign the app with an Apple
Development / Developer ID certificate (`SIGN_IDENTITY="Apple Development: …"
./build_macos.sh`). Also run `spctl --add --label PixelSync
/Applications/PixelSync.app` so Gatekeeper accepts the build.

## Optional: SukiSU / KernelSU root watchdog (auto-recovery)

Only needed if you want automatic recovery after reboot / process death without
relying on HyperOS Autostart. It is recovery-only (never bypasses Force Stop,
never touches framework/SELinux/SystemUI).

1. Grant root to ADB: open **SukiSU Ultra → Settings → enable “ADB root”**
   (or grant root to `Shell`). Verify:
   ```sh
   adb shell su -c id        # -> uid=0(root) ... context=u:r:ksu:s0
   ```
2. Install the script:
   ```sh
   adb push Android/root/pixelsync_watchdog.sh /data/local/tmp/
   adb shell su -c 'cp /data/local/tmp/pixelsync_watchdog.sh /data/adb/service.d/ && \
                    chmod 0755 /data/adb/service.d/pixelsync_watchdog.sh'
   ```
3. It starts at boot automatically. To start it now:
   ```sh
   adb shell su -c 'nohup /data/adb/service.d/pixelsync_watchdog.sh >/dev/null 2>&1 </dev/null &'
   ```
4. Inspect its log:
   ```sh
   adb shell su -c 'tail -40 /data/adb/pixelsync_watchdog.log'
   ```
   To remove it: `adb shell su -c 'rm /data/adb/service.d/pixelsync_watchdog.sh /data/adb/pixelsync_watchdog.*'`

**Note:** after each reboot the phone may drop ADB USB authorization; re-confirm
the “Allow USB debugging” dialog. This does not affect BLE sync (it is
independent of ADB).

## Troubleshooting

| Symptom | Action |
|---|---|
| Mac never finds phone | Ensure MacSync service is running (foreground notification visible); toggle Bluetooth off/on on the phone. |
| Connects then drops | Check battery optimisation is **Unrestricted**; on MIUI/HyperOS enable Autostart. |
| No notifications on Mac | Verify *Notification access* is granted and the app is enabled in the app list. |
| No call banners | Grant `READ_PHONE_STATE`; on some devices number requires `READ_CALL_LOG` (not requested by default). |
| Mac permission resets after rebuild | Expected with ad-hoc signing; re-grant Bluetooth/Notifications. |
