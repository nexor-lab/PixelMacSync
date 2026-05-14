# 🔄 PixelSync / MacSync

Un ecosistema di sincronizzazione invisibile, a bassissimo consumo e rigorosamente nativo tra dispositivi Google Pixel e macOS, basato esclusivamente su **Bluetooth Low Energy (BLE)**.

## 🎯 Obiettivo del Progetto
Creare un'alternativa leggera e "stock" a tool come KDE Connect o AirSync, progettata verticalmente per operare in background 24/7 senza impattare sull'autonomia dei dispositivi. 

Nessuna dipendenza da reti Wi-Fi, nessun socket TCP sempre aperto, nessun wakelock abusato. Solo payload BLE minimali scambiati tra due client nativi in modo puramente event-driven.

## ✨ Funzionalità (Must-Have)
- **Sincronizzazione Notifiche:** Inoltro istantaneo delle notifiche Android su macOS, con rendering nativo delle icone locali tramite mapping O(1).
- **Media Control:** Sincronizzazione dello stato multimediale e controllo remoto della riproduzione.
- **Stato Dispositivo:** Monitoraggio incrociato della percentuale di batteria e dello stato di ricarica.
- **Telemetria di Rete:** Visualizzazione su Mac del segnale, del tipo di rete (Wi-Fi, 4G, 5G) e della modalità DND/Audio del telefono.
- **Hotspot Remoto:** Toggle per l'attivazione/disattivazione dell'hotspot Android dalla menubar del Mac (tramite integrazione *Shizuku*).

## 🛠️ Architettura e Tech Stack
Il progetto segue una struttura a **Monorepo** per garantire il perfetto allineamento del contratto GATT.

### 📱 MacSync (Android - GATT Server)
- **Target:** Google Pixel 7 Pro / Pixel 11 Pro.
- **Linguaggio:** Kotlin + Jetpack Compose (MD3E / Monet).
- **Core:** `BluetoothGattServer`, `NotificationListenerService`, `Shizuku` API.

### 💻 PixelSync (macOS - GATT Client)
- **Target:** MacBook Pro (Apple Silicon M-Series).
- **Linguaggio:** Swift + SwiftUI (Liquid Glass / `NSVisualEffectView`).
- **Core:** Framework `CoreBluetooth`.

---

## 📜 Contratto GATT (Bluetooth LE)

### ⚙️ Logica di Trasferimento
- **Delimitatore:** Per le stringhe complesse viene utilizzato il carattere invisibile **Unit Separator (\u001F)**.
- **Protocol Versioning:** Caratteristica dedicata per il controllo della compatibilità del protocollo.
- **Binary Chunking:** Tutte le caratteristiche che trasferiscono testi (Notifiche, Media) includono un header di 2 byte per gestire la frammentazione oltre l'MTU:
  - `Byte 0`: Indice del chunk attuale (1-based).
  - `Byte 1`: Totale dei chunk previsti.
  - `Byte 2...N`: Payload effettivo (Stringa UTF-8).

> **⚠️ Vincolo di Serializzazione (FIFO):**
> Le trasmissioni frammentate sono rigorosamente serializzate lato Server (Android). Il GATT Server garantisce l'invio sequenziale dei chunk (`1/N`, `2/N`, `...`). Un nuovo payload frammentato non inizierà mai finché la trasmissione del payload precedente non sarà completamente terminata. Questo elimina la necessità di tracciare Message ID multipli lato Client.

### 📡 Servizio 1: System & Network (`b4250001-...`)
| Caratteristica | UUID | Proprietà | Formato Payload | Note |
| :--- | :--- | :--- | :--- | :--- |
| **Protocol Version**| `...0010-...` | `Read` | 1 Byte (Raw) | Versione attuale: `0x01` |
| **Pixel Battery** | `...0011-...` | `Notify` | 1 Byte (Raw) | 0-100% |
| **Mac Battery** | `...0012-...` | `Write` | 1 Byte (Raw) | Inviata dal Mac |
| **Network State** | `...0013-...` | `Notify` | Stringa (`Stato\u001FNome`) | 0=Off, 1=Cell, 2=WiFi |
| **Hotspot Toggle** | `...0014-...` | `Write` | 1 Byte (Raw) | 0x00=Off, 0x01=On |
| **Battery Detail** | `...0015-...` | `Notify` | 2 Byte (Raw) | Byte 0: Percentuale (0-100), Byte 1: In carica (0x00=No, 0x01=Sì) |
| **Audio Profile** | `...0016-...` | `Notify` | 1 Byte (Raw) | 0x00=Silenzioso, 0x01=Vibrazione, 0x02=Suoneria |
| **DND Mode** | `...0017-...` | `Notify` | 1 Byte (Raw) | Interruption Filter State |

### 💬 Servizio 2: Notifications (`b4250002-...`)
| Caratteristica | UUID | Proprietà | Formato Payload |
| :--- | :--- | :--- | :--- |
| **Active Notif.** | `...0021-...` | `Notify` | Chunked String (`Pkg\u001FTitle\u001FTxt`) |

### 🎵 Servizio 3: Media Control (`b4250003-...`)
| Caratteristica | UUID | Proprietà | Formato Payload |
| :--- | :--- | :--- | :--- |
| **Media State** | `...0031-...` | `Notify` | Chunked String (`State\u001FArtist\u001FTrk`) |
| **Remote Cmd** | `...0032-...` | `Write` | 1 Byte (Raw) | 1=Toggle, 2=Next, 3=Prev |

---

## 📂 Assets Mapping
Il Client macOS mappa i `PackageName` ricevuti su file locali in formato **.icns** situati in una **directory locale configurabile** (es. `~/Pictures/icone/`) tramite un dizionario a tempo di ricerca O(1).
