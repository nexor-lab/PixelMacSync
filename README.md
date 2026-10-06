# 🍏⇄🤖 PixelMacSync

> **本 Fork 说明（中文）**：这是一个把上游 [`LK024/PixelMacSync`](https://github.com/LK024/PixelMacSync)
> 移植到 **macOS 12.7 / Intel** 并新增 **电话事件、Root 远程热点、App 图标同步、音乐控件、手机生物识别解锁 Mac** 等功能的
> Fork。功能清单、实现方式、来源与改动、构建/安装、限制与安全说明，请见 **[`FORK.md`](FORK.md)**；
> 协议见 [`BLE_PROTOCOL.md`](BLE_PROTOCOL.md)，变更见 [`CHANGELOG.md`](CHANGELOG.md)，
> 测试见 [`TEST_REPORT.md`](TEST_REPORT.md)。

## 适配范围（本 Fork）

> 中文速览。下方意大利语 / 英语正文为上游原文，其中的旧要求（macOS 13+ / Android 16+ / 依赖 MacroDroid）已在本 Fork 更正。

| 组件 | 支持范围 |
| --- | --- |
| **macOS** | **macOS 12.7 Monterey 及以上**（Intel x86_64 实测；`ARCH=arm64` / `universal` 亦可构建） |
| **Android** | **Android 15（API 35）及以上**，`compileSdk/targetSdk 36`（test Android phone / HyperOS 实测） |
| **Root** | 需要（SukiSU / Magisk）以**真实**开关热点；**不再需要 MacroDroid** |
| **连接** | 仅 BLE 4.2+，不使用 Wi‑Fi / 局域网 / 云 / TCP |
| **对比上游** | 上游要求 macOS 13+ / Android 16+ 并依赖 MacroDroid，本 Fork 已下移适配并移除该依赖 |

## 🔓 本 Fork 新增：手机生物识别解锁 Mac

> 手机指纹/面容即可是你的“钥匙”：Mac 锁屏后手机收到通知，点按并完成**原生指纹/面容**验证，
> 手机用 **Android Keystore 私钥**对一次性随机挑战签名，Mac 验签通过后经 **macOS 原生 PAM**
> 放行解锁。**不存储、不传输、不输入 Mac 密码**，也不模拟键盘。

- **免密解锁**：仅走 BLE 蓝牙本地直连，无云端、无网络。算法为公开成熟的 **ECDSA P-256 + SHA-256**；
  私钥受指纹保护、永不导出；挑战为 **一次性 32 字节随机数**、有有效期、用后即废，防重放。
- **抗伪装**：其它蓝牙设备即使知道设备 ID/蓝牙地址，没有手机私钥就**签不出有效签名**，无法解锁；
  可随时在两端撤销授权。
- **绑定/解绑**：在安卓「已连接的设备」中**绑定指纹解锁**；绑定需 **Mac 登录密码**（系统授权框，
  不读取不保存）+ **手机指纹**；**解绑需指纹 + 二次确认**（红色）。
- **距离自动锁定**：手机远离 Mac（BLE 断开）一段时间后**自动锁定 Mac 屏幕**。
- **远程唤醒**：靠近重连后**唤醒屏幕**并可用手机指纹解锁。
- **安全策略开关**（设置 →「远程解锁安全策略」）：`设备远离自动锁定`、`远程唤醒`（关闭后隐藏绑定/
  解锁相关操作，仅保留断开与删除）。
- **兼容**：锁屏授权走 macOS 原生 **PAM**，模块为 **Intel + Apple Silicon 通用二进制**；密码解锁
  路径保持不变，随时可用。

> [!WARNING]
> ⚠️ **需要一定技术能力，且并非开箱即用。** 要启用「手机生物识别解锁 Mac」等功能，需自行完成下列操作（涉及**系统级改动**，请勿在主力机上贸然操作）：
> 1. 安装 App：Android 安装 APK；macOS 把 `PixelSync.app` 放入 `/Applications`。
> 2. 以**管理员权限**安装 PAM 模块：`cd MacOS/pam && ./build.sh && sudo ./install.sh`（会向 `/etc/pam.d/screensaver` 添加 `auth sufficient` 一行，**保留密码回退**；可 `sudo ./uninstall.sh` 回退）。
> 3. 在安卓端「已连接的设备」完成**绑定**：Mac 输入登录密码 + 手机指纹（密钥各自生成，不共享）。
> 4. 手机与 Mac 保持 BLE 连接，锁屏后手机点通知 → 指纹 → 免密解锁。
>
> 无相关经验者请勿尝试；升级/换机需重装 PAM 并重新配对。

### PixelSync (macOS) ⇄ MacSync (Android)
(🇬🇧 Scroll down for the English readme, READ IT!)

PixelMacSync è un progetto open-source che fornisce un’integrazione nativa tra macOS e Android tramite **Bluetooth Low Energy (BLE)**.
L’obiettivo è offrire sincronizzazione di notifiche, telemetria e controllo remoto senza dipendere da Wi‑Fi, cloud o connessioni TCP persistenti.

>[!WARNING]
> ⚠️ **NOTA BENE PRIMA DI COMPILARE (Leggere Attentamente!)**
>
> Questo progetto è rilasciato esclusivamente sotto forma di **codice sorgente**. Prima di procedere con la build, ci sono due configurazioni fondamentali da sistemare per evitare problemi funzionali o comportamenti anomali:
>
> **1. Sicurezza e Conflitto tra Dispositivi (Cambia gli UUID!)**
> Attualmente, il pairing Bluetooth si basa su UUID codificati nel sorgente (elencati nella sezione "Protocollo BLE & UUIDs" più in basso). Se due persone nella stessa stanza utilizzano questa app con il codice originale, i loro Mac potrebbero incrociarsi e intercettare le notifiche a vicenda. 
> **Prima di compilare**, genera 4 nuovi UUID univoci (uno per il Service principale e tre per i canali dati) e sostituiscili nei file indicati qui sotto, **assicurandoti che i codici inseriti combacino esattamente** tra i due sistemi operativi:
> * Android: `GattServerManager.kt` e `BLEAdvertiser.kt`
> * macOS: `BLEManager.swift`
> 
> **2. Controllo Hotspot Remoto (root, senza MacroDroid)**
> In questo fork il toggle dell'Hotspot dal Mac **non usa più MacroDroid**: il comando viene eseguito realmente via root
> (`cmd wifi start-softap` / `stop-softap`) usando il profilo SoftAP già salvato sul telefono. Serve un telefono con
> **root** (SukiSU/Magisk) e il consenso root per l'app `it.luigi.macsync`.
>
> ---
> ℹ️ *Nota sulla Privacy Android (Pallino della localizzazione)*: Per inviare al Mac il nome reale della rete Wi-Fi (SSID), l'app Android interroga API di rete che richiedono il permesso di localizzazione. Questo accenderà l'indicatore blu della privacy sul telefono. Se preferisci nasconderlo nativamente, revoca il permesso `ACCESS_FINE_LOCATION` (consenti posizione esatta) dalle impostazioni di Android: il server farà un fallback automatico inviando la stringa fissa `"Wi-Fi"`, spegnendo l'indicatore per sempre senza causare crash.

## 🎯 Obiettivi di Progetto

* Solo Bluetooth Low Energy (BLE)
* Sincronizzazione stateless
* Architettura incentrata sul risparmio energetico (Battery-first)
* Client nativi per Android e macOS
* Nessun servizio cloud o server esterno
* Nessuna dipendenza dalla rete Wi-Fi locale
* Comunicazione event-driven

> 💡 **Filosofia di Design**
>
> PixelMacSync è intenzionalmente focalizzato su una sincronizzazione a bassa larghezza di banda e guidata dagli eventi esclusivamente tramite Bluetooth Low Energy.
> 
> Funzionalità che richiederebbero grandi trasferimenti di dati, connessioni di rete persistenti, servizi cloud o un impatto significativo sulla batteria sono considerate rigorosamente al di fuori dello scopo di questo progetto.

---

## 📋 Requisiti

| Componente     | Requisito minimo (fork)                        |
| -------------- | ---------------------------------------------- |
| **Android** | Android 15+ (API 35, target 36) — testato su test Android phone / HyperOS |
| **macOS** | macOS 12.7 Monterey+ (Intel x86_64; `ARCH=arm64`/`universal` disponibili) |
| **Root** | Richiesto (SukiSU/Magisk) per controllare davvero l'Hotspot — **MacroDroid non più necessario** |
| **Bluetooth** | BLE 4.2+ su entrambi i dispositivi             |

---

## ✨ Features Principali

* 📊 **Telemetria in tempo reale**: visualizza dalla Menu Bar del Mac batteria, stato di ricarica, tipo di connessione, segnale e altri indicatori del telefono.
* 🔔 **Inoltro notifiche**: le notifiche Android selezionate vengono inoltrate a macOS come notifiche native, mantenendo le icone originali quando disponibili.
* 🔄 **Reconnection sync**: alla riconnessione Bluetooth, macOS richiede uno snapshot completo dello stato attuale per riallineare notifiche e telemetria.
* 🛡️ **Filtro anti-doppioni**: le notifiche “summary” o raggruppate di alcune app vengono filtrate per ridurre rumore e duplicati.
* 🧹 **Dismiss bidirezionale**: una notifica aperta o rimossa su un lato viene sincronizzata anche sull’altro lato, nei limiti delle API disponibili.
* 🖱️ **Apertura app dinamica**: il mapping tra package name Android e app/PWA su macOS è configurabile e salvato in un file JSON locale.
* 🚀 **Controllo hotspot remoto (root)**: accendi e spegni davvero l’hotspot del telefono dal Mac via root + comando di sistema, con stato reale.

---

## 🔍 How It Works

PixelMacSync è diviso in due client che dialogano tramite un server GATT personalizzato.

### 📱 Android Client — MacSync

L’app Android agisce come **Peripheral / GATT Server** ed è ottimizzata per la serie Google Pixel.

* **Motore BLE in background**: usa un *Foreground Service* per restare attivo anche durante i cicli di Doze.
* **Notification interceptor & snapshot**: intercetta le notifiche e genera uno snapshot dello stato attivo quando richiesto dal Mac.
* **GattServerManager & broadcasts**: riceve comandi dal Mac e li inoltra ai componenti interni dell’app.
* **Hotspot via root**: esegue realmente `cmd wifi start-softap` / `stop-softap` come root e riporta lo stato reale (nessun intent verso terzi).

<p align="center">
  <img src="https://github.com/user-attachments/assets/1a3caa7b-d75c-4b5b-8d17-f07829351716" width="300" />
  <img src="https://github.com/user-attachments/assets/cd07089a-2362-41d9-a85e-0e1a90a205dc" width="300" />
</p>

### 💻 macOS Client — PixelSync

L’app macOS agisce come **Central / GATT Client** e vive nella Menu Bar.

* **CoreBluetooth & Combine**: gestisce connessione, riconnessione e aggiornamenti di stato.
* **UNUserNotificationCenterDelegate**: intercetta le interazioni dell’utente con le notifiche e le sincronizza con Android.
* **Configurazione disaccoppiata**: usa un file JSON locale per le regole di mapping delle app.

<p align="center">
  <img src="https://github.com/user-attachments/assets/4a4723fe-4591-4464-9a53-d1822d0aa46b" />
</p>

<p align="center">
  <img src="https://github.com/user-attachments/assets/8387a26b-e0d5-48ef-9cc7-ea965ef9079e" />
</p>

---

## 📡 Protocollo BLE & UUIDs

Per garantire una comunicazione sicura e univoca, il progetto utilizza i seguenti UUID immutabili (**⚠️ da modificare obbligatoriamente prima della compilazione su entrambi i dispositivi**):

* **Service Principale:** `58DF214B-9942-45A5-BAF9-7B24F5D0232C`
* **Canale Telemetria (Read/Notify):** `CEFB6548-6C8A-4D25-A086-C8A69D3F6625`
* **Canale Notifiche (Notify):** `6E6C9609-9FFA-42E2-A882-B0C4398D58DE`
* **Canale Comandi (Write):** `586B06E6-CCC5-44B8-BFD9-5D2514A67842`

Il pacchetto telemetrico è formattato in questo ordine:
`Batteria | In carica | Rete/SSID | Segnale | isWifi | isHotspotActive`

Le notifiche vengono inoltrate con prefisso `POST\u001F` o `REMOVE\u001F`.
Il canale comandi supporta `HOTSPOT_ON`, `HOTSPOT_OFF`, `SYNC_REQ` e `KILL\u001F[ID_Notifica]`.
Il `POST` può includere un 7º campo `replyable` (1/0: se la notifica supporta la risposta inline).
Al click del banner il Mac apre l'App mappata in `app_mappings.json` (solo per nome App); se non mappata, nessuna azione (niente selettore involontario).
La notifica sul telefono viene cancellata **solo** quando la si rimuove dal Centro Notifiche, non al click.
Risposta inline: `REPLY\u001F[id]\u001F[base64(testo)]` → l'Android usa il `RemoteInput` della notifica.

---

## 🛠 Setup e Configurazione

### Lato Android

1. Compila l’APK in modalità **Release** tramite Android Studio.
2. Vai in *Impostazioni → App → MacSync → Batteria* e imposta **"Senza restrizioni"**.
3. Concedi i permessi richiesti al primo avvio: Bluetooth, Posizione, Dispositivi Vicini.
4. Abilita **MacSync Notifiche** nell’Accesso alle Notifiche di sistema e seleziona le app desiderate dalla UI.
5. **Hotspot remoto (root):** concedi il permesso root all’app. Il toggle Hotspot dal Mac usa
   `cmd wifi start-softap` / `stop-softap` e riporta lo stato reale. **MacroDroid non è più necessario.**
6. **Autorizzazione:** in *Impostazioni → Metodo di autorizzazione* scegli **Root** (shell `su`) oppure **Shizuku** (senza root). Con Shizuku, installa l’app dall’[link ufficiale](https://github.com/RikkaApps/Shizuku) e concedi il permesso.

> **Harness di test (non distribuito):** il modulo Gradle `:notifytest` genera un’app separata che pubblica notifiche simulate (una normale senza azioni di risposta, una chat con `RemoteInput`) per verificare click/Open e risposta inline. Abilitala nella lista notifiche di PixelSync, poi usa i pulsanti o `adb shell am start -n it.luigi.macsync.notifytest/.MainActivity --es cmd post_x|post_chat|clear`.

### Lato macOS

1. Apri il progetto in Xcode e compila in modalità **Release**.
2. Per consentire il salvataggio del file `app_mappings.json` nella cartella utente, disabilita l’**App Sandbox** in *Signing & Capabilities* prima della build.
3. Al primo avvio, concedi il permesso per le Notifiche di macOS.
4. Il sistema scansionerà automaticamente il BLE e richiederà il primo *Reconnection Sync*.

---

## ⚠️ Limitazioni Note

* **Hotspot — root richiesto:** il controllo remoto dell’hotspot usa root; senza root resta solo la lettura dello stato (nessun MacroDroid).
* **Dismiss notifiche — solo al click:** la sincronizzazione del dismiss da Mac ad Android avviene solo quando si clicca sulla notifica macOS, non quando la si swipe via.
* **Notifiche di gruppo:** alcune app inviano notifiche summary raggruppate; il filtro riduce il rumore ma il comportamento finale dipende dall’app stessa.

---

## 📂 Assets Mapping

Il client macOS mappa i `PackageName` ricevuti su file locali in formato **.icns** situati in `~/Pictures/icone/` tramite un dizionario a tempo di ricerca O(1).
<p align="center">
<img width="928" height="515" alt="Screenshot 2026-06-05 alle 11 47 53" src="https://github.com/user-attachments/assets/c8b705e1-d121-4662-9f16-ce8afe234c3d" />
</p>

<br><br><br>

---

## 🇬🇧 English Version

### PixelSync (macOS) ⇄ MacSync (Android)

PixelMacSync is an open-source project that provides native integration between macOS and Android via **Bluetooth Low Energy (BLE)**.
The goal is to offer notification synchronization, telemetry, and remote control without relying on Wi-Fi, cloud services, or persistent TCP connections.

>[!WARNING]
> ⚠️ **IMPORTANT BEFORE COMPILING (Read Carefully!)**
>
> This project is released strictly as **source code**. Before proceeding with the build, there are two crucial configurations to address to avoid functional issues or unexpected behaviors:
>
> **1. Security and Device Conflict (Change the UUIDs!)**
> Currently, Bluetooth pairing relies on hardcoded UUIDs (listed in the "BLE Protocol & UUIDs" section below). If two people in the same room use this app with the original code, their Macs might cross-connect and intercept each other's notifications. 
> **Before compiling**, generate 4 new unique UUIDs (one for the main Service and three for the data channels) and replace them in the following files, **ensuring that the inserted codes match exactly** across both operating systems:
> * Android: `GattServerManager.kt` and `BLEAdvertiser.kt`
> * macOS: `BLEManager.swift`
> 
> **2. Remote Hotspot Control (root, no MacroDroid)**
> In this fork the Mac hotspot toggle **no longer uses MacroDroid**: the command is executed for real via root
> (`cmd wifi start-softap` / `stop-softap`) using the phone's saved SoftAP profile. A **rooted** phone
> (SukiSU/Magisk) and a root grant for `it.luigi.macsync` are required.
>
> ---
> ℹ️ *Android Privacy Note (Location dot)*: To display the real name of your Wi-Fi network (SSID) on the Mac, the Android app queries network APIs that require location permissions. This will turn on the blue privacy indicator on the phone. If you prefer to hide it natively, revoke the `ACCESS_FINE_LOCATION` permission from Android settings: the server will automatically fallback to sending the static string `"Wi-Fi"`, turning off the indicator permanently without causing crashes.

## 🎯 Design Goals

* Bluetooth Low Energy only
* Stateless synchronization
* Battery-first architecture
* Native Android and macOS clients
* No cloud services
* No Wi‑Fi dependency
* Event-driven communication

> 💡 **Design Philosophy**
>
> PixelMacSync is intentionally focused on low-bandwidth,
> event-driven synchronization over Bluetooth Low Energy.
>
> Features that would require large payload transfers,
> persistent networking, cloud services or significant
> battery impact are considered outside the scope of the project.

---

## 📋 Requirements

| Component      | Minimum Requirement (fork)                    |
| -------------- | --------------------------------------------- |
| **Android** | Android 15+ (API 35, target 36) — tested on test Android phone / HyperOS |
| **macOS** | macOS 12.7 Monterey+ (Intel x86_64; `ARCH=arm64`/`universal` also buildable) |
| **Root** | Required (SukiSU/Magisk) for real Hotspot control — **MacroDroid no longer needed** |
| **Bluetooth** | BLE 4.2+ on both devices                      |

---

## ✨ Main Features

* 📊 **Real-time Telemetry**: View your phone's battery, charging status, connection type, signal strength, and other indicators directly from the Mac Menu Bar.
* 🔔 **Notification Forwarding**: Selected Android notifications are forwarded to macOS as native notifications, keeping their original icons when available.
* 🔄 **Reconnection Sync**: Upon Bluetooth reconnection, macOS requests a complete state snapshot to realign notifications and telemetry.
* 🛡️ **Anti-Duplicate Filter**: "Summary" or grouped notifications from certain apps are filtered out to reduce noise and duplicates.
* 🧹 **Bidirectional Dismiss**: A notification opened or dismissed on one side is synced to the other side, within the limits of the available APIs.
* 🖱️ **Dynamic App Launching**: The mapping between Android package names and macOS apps/PWAs is configurable and saved in a local JSON file.
* 🚀 **Remote Hotspot Control (root)**: Turn the phone's hotspot on and off from the Mac for real via root + system command, with real state.
* 🔓 **Phone biometric unlock of the Mac (new)**: Lock the Mac and unlock it with the phone's fingerprint/face. The phone signs a one-time random challenge with a fingerprint-gated **Android Keystore** key (**ECDSA P-256 / SHA-256**); the Mac verifies it against the paired public key and authorizes the unlock through native macOS **PAM** — no Mac password is stored, transmitted, or typed.
  * **Spoof-resistant**: another Bluetooth device cannot forge the signature without the phone's private key; the challenge is single-use, expiring, and revocable.
  * **Bind/unbind** from the Android "connected devices" list: binding requires the **Mac login password** (system dialog) + **phone fingerprint**; unbinding requires a fingerprint and a confirmation.
  * **Auto-lock when away** (phone out of range) and **remote wake** (wake + unlock on reconnect), controllable via two switches under Settings → *Remote unlock security*.

> [!WARNING]
> ⚠️ **Requires technical ability — not plug-and-play.** To enable the phone-unlock feature you must perform the setup yourself (these are **system-level changes**; do not try them on a daily driver unless you know what you are doing):
> 1. Install the apps: Android APK; put `PixelSync.app` into `/Applications`.
> 2. Install the PAM module **as admin**: `cd MacOS/pam && ./build.sh && sudo ./install.sh` (adds one `auth sufficient` line to `/etc/pam.d/screensaver`, **keeps the password fallback**; revert with `sudo ./uninstall.sh`).
> 3. **Pair** from the Android "connected devices" list: Mac login password + phone fingerprint (keys are generated per device, never shared).
> 4. Keep the phone and Mac on BLE; lock the Mac, tap the phone notification, fingerprint → passwordless unlock.
>
> Reinstalling/upgrading or switching machines requires reinstalling the PAM module and re-pairing.


---

## 🔍 How It Works

PixelMacSync is divided into two clients that communicate via a custom GATT server.

### 📱 Android Client — MacSync

The Android app acts as the **Peripheral / GATT Server** and is optimized for the Google Pixel series.

* **Background BLE Engine**: Uses a *Foreground Service* to stay active even during Doze cycles.
* **Notification Interceptor & Snapshot**: Intercepts notifications and generates a snapshot of the active state when requested by the Mac.
* **GattServerManager & Broadcasts**: Receives commands from the Mac and forwards them to internal app components.
* **Hotspot via root**: Actually runs `cmd wifi start-softap` / `stop-softap` as root and reports the real state (no third-party intents).

<p align="center">
  <img src="https://github.com/user-attachments/assets/1a3caa7b-d75c-4b5b-8d17-f07829351716" width="300" />
  <img src="https://github.com/user-attachments/assets/cd07089a-2362-41d9-a85e-0e1a90a205dc" width="300" />
</p>

### 💻 macOS Client — PixelSync

The macOS app acts as the **Central / GATT Client** and lives in the Menu Bar.

* **CoreBluetooth & Combine**: Manages connection, reconnection, and state updates.
* **UNUserNotificationCenterDelegate**: Intercepts user interactions with notifications and syncs them with Android.
* **Decoupled Configuration**: Uses a local JSON file for app mapping rules.

<p align="center">
  <img src="https://github.com/user-attachments/assets/4a4723fe-4591-4464-9a53-d1822d0aa46b" />
</p>

<p align="center">
  <img src="https://github.com/user-attachments/assets/8387a26b-e0d5-48ef-9cc7-ea965ef9079e" />
</p>

---

## 📡 BLE Protocol & UUIDs

To ensure secure and unique communication, the project uses the following immutable UUIDs (**⚠️ these must be changed before compiling on both devices**):

* **Main Service:** `58DF214B-9942-45A5-BAF9-7B24F5D0232C`
* **Telemetry Channel (Read/Notify):** `CEFB6548-6C8A-4D25-A086-C8A69D3F6625`
* **Notification Channel (Notify):** `6E6C9609-9FFA-42E2-A882-B0C4398D58DE`
* **Command Channel (Write):** `586B06E6-CCC5-44B8-BFD9-5D2514A67842`

The telemetry packet is formatted in this order:
`Battery | Charging | Network/SSID | Signal | isWifi | isHotspotActive`

Notifications are forwarded with the prefix `POST\u001F` or `REMOVE\u001F`.
The command channel supports `HOTSPOT_ON`, `HOTSPOT_OFF`, `SYNC_REQ`, and `KILL\u001F[Notification_ID]`.
A `POST` may carry a 7th `replyable` field (1/0: whether the notification supports inline reply).
Clicking a banner opens the app mapped in `app_mappings.json` (app name only); if unmapped it does nothing (no accidental picker).
The phone notification is cleared **only** when you dismiss it in Notification Center, not on click.
Inline reply: `REPLY\u001F[id]\u001F[base64(text)]` → Android injects it via the notification's `RemoteInput`.

---

## 🛠 Setup and Configuration

### Android Side

1. Compile the APK in **Release** mode via Android Studio.
2. Go to *Settings → Apps → MacSync → Battery* and set it to **"Unrestricted"**.
3. Grant required permissions on first launch: Bluetooth, Location, Nearby Devices.
4. Enable **MacSync Notifications** in the system's Notification Access and select the desired apps from the UI.
5. **Remote Hotspot (root):** grant root to the app. The Mac hotspot toggle uses
   `cmd wifi start-softap` / `stop-softap` and reports the real state. **MacroDroid is no longer needed.**
6. **Authorization:** in *Settings → Authorization method* choose **Root** (`su` shell) or **Shizuku** (without root). For Shizuku, install the app from the [official link](https://github.com/RikkaApps/Shizuku) and grant permission.

> **Test harness (not shipped):** the `:notifytest` Gradle module builds a separate app that posts simulated notifications (a plain one with no reply action, and a chat one with `RemoteInput`) to verify click/Open and inline reply. Enable it in PixelSync's notification list, then use the buttons or `adb shell am start -n it.luigi.macsync.notifytest/.MainActivity --es cmd post_x|post_chat|clear`.

### macOS Side

1. Open the project in Xcode and compile in **Release** mode.
2. To allow saving the `app_mappings.json` file in the user folder, disable **App Sandbox** in *Signing & Capabilities* before building.
3. On first launch, grant permission for macOS Notifications.
4. The system will automatically scan for BLE and request the first *Reconnection Sync*.

---

## ⚠️ Known Limitations

* **Hotspot — root required:** Remote control of the hotspot uses root; without root only state reading works (no MacroDroid).
* **Notification Dismiss — Click Only:** The dismiss synchronization from Mac to Android only occurs when the macOS notification is clicked, not when swiped away.
* **Open by package mapping:** clicking a banner opens the app mapped in `app_mappings.json`. Webpage/URL mapping is not supported and there is **no app picker on notifications**; unmapped packages simply do nothing on click. Add mappings by editing `~/Documents/MacSync/app_mappings.json` (`"<android.package>": "<Mac App name>"`).
* **Banner click does not dismiss the phone:** the phone notification is cleared only when the banner is dismissed in Notification Center.
* **Inline reply — app dependent:** Only works when the origin app exposes a free-form `RemoteInput` action (WeChat/Telegram/SMS usually do; a social feed such as X often does not). The Mac only shows the reply field for notifications Android flags `replyable=1`; unanswered apps return `REPLY_RESULT … no_reply_action`.
* **Inline reply — needs native notifications:** the reply text field comes from `UserNotifications`; the ad-hoc release build falls back to `osascript`, which cannot show custom actions. Sign with an Apple certificate for the inline reply UI.
* **Group Notifications:** Some apps send grouped summary notifications; the filter reduces noise, but the final behavior depends on the app itself.

---

## 📂 Assets Mapping

The macOS client maps received `PackageNames` to local files in **.icns** format located in `~/Pictures/icone/` using an O(1) lookup dictionary.
<p align="center">
<img width="928" height="515" alt="Screenshot 2026-06-05 alle 11 47 53" src="https://github.com/user-attachments/assets/c8b705e1-d121-4662-9f16-ce8afe234c3d" />
</p>
