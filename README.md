# 🍏🤖 MacSync: L'Ecosistema Ibrido macOS - Android

MacSync è un progetto open-source nato per colmare il divario tra macOS e Android, ricreando un'integrazione profonda in perfetto stile "Ecosistema Apple". Sfruttando una connessione **Bluetooth Low Energy (BLE)** ultra-efficiente, MacSync permette al tuo Mac e al tuo smartphone Android (ottimizzato per la serie Google Pixel) di comunicare in background senza pesare sulla batteria.

---

## ✨ Funzionalità Principali

* 📊 **Telemetria in Tempo Reale:** Controlla lo stato del tuo telefono direttamente dalla Menu Bar del Mac. Visualizza percentuale della batteria, stato di ricarica, tipo di connessione (Wi-Fi, 4G, 4G+, 5G, E) e potenza del segnale.
* 🔔 **Inoltro Notifiche (MTU Ottimizzato):** Le notifiche del telefono vengono intercettate (tramite White-list) e inoltrate al Mac. Appaiono come notifiche native di macOS con le icone originali. Per eludere i limiti di banda (MTU) del Bluetooth, il sistema comprime gli UUID in micro-ID alfanumerici, garantendo un trasferimento fulmineo.
* 🖱️ **Apertura App Dinamica (Smart Mapping):** Cliccando su una notifica ricevuta sul Mac, si aprirà istantaneamente l'app macOS o la PWA corrispondente. L'associazione tra *package name* di Android e l'app di macOS avviene tramite un processo di auto-apprendimento interattivo e viene salvata in un file di configurazione (`app_mappings.json`).
* 🧹 **Reverse Dismiss (Sync Bidirezionale):** Quando interagisci con una notifica cliccandola su macOS, il Mac invia un comando invisibile al telefono, eliminando istantaneamente la notifica nativa dal centro notifiche di Android.
* 🚀 **Controllo Hotspot Remoto:** Accendi e spegni l'Hotspot Wi-Fi del telefono con un clic dal Mac. Supporta il sync bidirezionale e preserva l'**Accelerazione Hardware Tethering** nativa per massime prestazioni sul 5G.

---

## 🏗 Struttura del Progetto

Il progetto è diviso in due macro-componenti che dialogano tramite un server GATT (Generic Attribute Profile) personalizzato.

### 📱 1. Android Client (Kotlin)
L'applicazione Android agisce come **Peripheral / GATT Server**.
* **Motore BLE in Background:** Sfrutta un *Foreground Service* per mantenere l'antenna attiva e in ascolto, sopravvivendo ai cicli di Doze.
* **Notification Interceptor:** Estrae il payload delle notifiche e le smista via BLE, associando a ciascuna un mini-ID univoco.
* **GattServerManager & Broadcasts:** Ascolta i comandi in ingresso dal Mac (tramite canale WRITE). Quando riceve il comando `KILL`, propaga un Intent interno per distruggere la notifica.
* **MacroDroid Bridge:** Aggira i blocchi di sicurezza di Android 14 sulle API di sistema delegando l'accensione dell'Hotspot a MacroDroid tramite `Intent Broadcast`.

### 💻 2. macOS Client (Swift)
L'applicazione macOS agisce come **Central** e risiede comodamente nella Menu Bar.
* **CoreBluetooth & Combine:** Gestisce la connessione reattiva, agganciandosi alla cache di sistema per riconnessioni istantanee al risveglio dallo stop.
* **UNUserNotificationCenterDelegate:** Intercetta i click dell'utente sulle notifiche, lancia processi di sistema (`Process`) per aprire le app tramite `NSWorkspace` e innesca la risposta verso Android.
* **Configurazione Disaccoppiata:** Utilizza un file JSON puro in `/Documenti/MacSync/` per le regole di mapping delle app, evitando l'hardcoding e rendendo il sistema flessibile per modifiche manuali esterne.

---

## 📡 Protocollo BLE & UUIDs

Per garantire una comunicazione sicura e univoca, il progetto utilizza i seguenti UUID immutabili:

* **Service Principale:** `E20A39F4-73F5-4BC4-A12F-17D1AD07A961`
* **Canale Telemetria (Read/Notify):** `33333333-73F5-4BC4-A12F-17D1AD07A961`
* **Canale Notifiche (Notify):** `22222222-73F5-4BC4-A12F-17D1AD07A961`
* **Canale Comandi (Write):** `44444444-73F5-4BC4-A12F-17D1AD07A961`

*(Il pacchetto telemetrico è formattato in questo ordine: `Batteria | In Carica | Rete/SSID | Segnale | isWifi | isHotspotActive`, separati dal delimitatore `\u001F`. Il canale comandi supporta `HOTSPOT_ON`, `HOTSPOT_OFF` e `KILL\u001F[ID_Notifica]`)*

---

## 🛠 Setup e Configurazione

### Lato Android
1. Compila l'APK in modalità **Release** tramite Android Studio.
2. Vai in *Impostazioni -> App -> MacSync -> Batteria* e imposta **"Senza restrizioni"** (fondamentale per evitare la chiusura del listener in background).
3. Concedi i permessi richiesti al primo avvio (Bluetooth, Posizione, Dispositivi Vicini).
4. Abilita **MacSync Notifiche** nell'Accesso alle Notifiche di sistema.
5. **Configurazione MacroDroid (Solo Hotspot):**
   * Macro 1: Attivatore `it.luigi.macsync.HOTSPOT_ON` -> Azione: *Abilita Hotspot*.
   * Macro 2: Attivatore `it.luigi.macsync.HOTSPOT_OFF` -> Azione: *Disabilita Hotspot*.

### Lato macOS
1. Apri il progetto in Xcode e compila in modalità **Release**.
2. **Accesso alla cartella Documenti:** Per consentire all'app di salvare il file `app_mappings.json` nella tua vera cartella utente e non nel filesystem nascosto, disabilita l'**App Sandbox** dalla scheda *Signing & Capabilities* su Xcode prima della build. (In alternativa, il file verrà confinato in `~/Library/Containers/[Bundle_ID]/Data/Documents/`).
3. Al primo avvio, concedi il permesso per le Notifiche di macOS. Il sistema scansionerà automaticamente l'etere per agganciare il server GATT del Pixel.

---
*Sviluppato a scopo didattico e personale per superare i limiti di comunicazione tra ecosistemi informatici differenti.*
