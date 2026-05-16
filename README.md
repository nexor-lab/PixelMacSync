# 🔄 PixelSync / MacSync

Un ecosistema di sincronizzazione invisibile, a bassissimo consumo e rigorosamente nativo tra dispositivi Google Pixel e macOS, basato esclusivamente su **Bluetooth Low Energy (BLE)**.

## 🎯 Obiettivo del Progetto
Creare un'alternativa ultra-leggera e "stock" a tool come KDE Connect o AirSync, progettata verticalmente per operare in background 24/7 senza impattare sull'autonomia dei dispositivi. 

Seguendo il principio **YAGNI (You Aren't Gonna Need It)**, l'app si concentra solo sullo stretto indispensabile: nessuna dipendenza da reti Wi-Fi, nessun socket TCP sempre aperto, nessun servizio in background inutile (come i controlli multimediali). Solo payload BLE minimali scambiati tra due client nativi in modo puramente event-driven.

## ✨ Funzionalità
- **Sincronizzazione Notifiche:** Inoltro istantaneo delle notifiche Android su macOS (con white-list delle app configurabile) e rendering nativo delle icone tramite mapping locale.
- **Telemetria Unificata:** Visualizzazione nella Menu Bar del Mac della percentuale di batteria del Pixel, dello stato di ricarica, della potenza del segnale cellulare e del tipo di rete (Wi-Fi, 4G, 5G).
- **Hotspot Remoto (Shizuku):** Interruttore nativo su macOS per accendere e spegnere istantaneamente l'hotspot del Pixel. Sfrutta privilegi ADB/Shell tramite Shizuku e chiamate dirette (tramite manipolazione a basso livello dei `Parcel`) al servizio tethering di Android, eludendo le restrizioni di sistema.

## 🛠️ Architettura e Tech Stack
Il progetto segue una struttura a **Monorepo** per garantire il perfetto allineamento del contratto GATT.

### 📱 MacSync (Android - GATT Server)
- **Target:** Google Pixel (Testato su Pixel 7 Pro, predisposto per Android 16+).
- **Linguaggio:** Kotlin + Jetpack Compose (Material Design 3).
- **Core:** `BluetoothGattServer`, `NotificationListenerService`, `Shizuku` API (manipolazione `ITetheringConnector`).

### 💻 PixelSync (macOS - GATT Client)
- **Target:** MacBook Pro (Apple Silicon M-Series).
- **Linguaggio:** Swift + SwiftUI.
- **Core:** Framework `CoreBluetooth`, `MenuBarExtra` (Menu Bar App fluttuante), `UserNotifications`.

---

## 📜 Contratto GATT (Bluetooth LE)

L'architettura BLE è stata drasticamente semplificata per massimizzare la stabilità e la velocità. Utilizza un singolo Servizio Primario e tre Caratteristiche dedicate. 

- **Service UUID:** `E20A39F4-73F5-4BC4-A12F-17D1AD07A961`
- **Delimitatore Payload:** Per i dati composti viene utilizzato il carattere invisibile **Unit Separator (`\u001F`)**.

### 📡 Caratteristiche del Servizio

| Nome | UUID | Proprietà | Formato Payload (UTF-8 String) |
| :--- | :--- | :--- | :--- |
| **Telemetry**| `33333333-73F5-4BC4-A12F-17D1AD07A961` | `Read` / `Notify` | `Batteria\u001FInCarica\u001FRete\u001FSegnale\u001FWifi`<br>*(es. `85\u001Ftrue\u001F5G\u001F4\u001Ffalse`)* |
| **Notifications** | `22222222-73F5-4BC4-A12F-17D1AD07A961` | `Notify` | `PackageName\u001FTitolo\u001FTesto` |
| **Commands** | `44444444-73F5-4BC4-A12F-17D1AD07A961` | `Write` | Stringhe dirette: `"HOTSPOT_ON"` oppure `"HOTSPOT_OFF"` |

---

## 📂 Assets Mapping delle Notifiche
Per mantenere le trasmissioni BLE ultra-leggere, il server Android non trasmette i bitmap delle icone. Il Client macOS mappa i `PackageName` ricevuti sulle icone salvate in locale.
- **Directory:** `~/Pictures/MacSyncIcons/`
- **Formato:** File `.png` nominati con il bundle ID dell'app (es. `com.whatsapp.png`).
- Se l'icona è presente, macOS la inietta nativamente nell'allegato della notifica (`UNNotificationAttachment`); in caso contrario, mostra la notifica standard.
