# 🍏🤖 MacSync: L'Ecosistema Ibrido macOS - Android

MacSync è un progetto open-source nato per colmare il divario tra macOS e Android, ricreando un'integrazione profonda in perfetto stile "Ecosistema Apple". Sfruttando una connessione **Bluetooth Low Energy (BLE)** ultra-efficiente, MacSync permette al tuo Mac e al tuo smartphone Android (ottimizzato per la serie Google Pixel) di comunicare in background senza pesare sulla batteria.

---

## ✨ Funzionalità Principali

* 📊 **Telemetria in Tempo Reale:** Controlla lo stato del tuo telefono direttamente dalla Menu Bar del Mac. Visualizza percentuale della batteria, stato di ricarica, tipo di connessione (Wi-Fi, 4G, 4G+, 5G, E) e potenza del segnale.
* 🔔 **Inoltro Notifiche Nativo:** Le notifiche del tuo telefono vengono intercettate (tramite una White-list personalizzabile) e inoltrate al Mac. Appaiono come notifiche native di macOS, complete delle icone originali delle app.
* 🚀 **Controllo Hotspot Remoto:** Accendi e spegni l'Hotspot Wi-Fi del telefono con un clic dal Mac. Supporta il sync bidirezionale (se lo accendi dal telefono, il Mac si aggiorna) e preserva l'**Accelerazione Hardware Tethering** del chip Tensor per massime prestazioni sul 5G.

---

## 🏗 Struttura del Progetto

Il progetto è diviso in due macro-componenti che dialogano tramite un server GATT (Generic Attribute Profile) personalizzato.

### 📱 1. Android Client (Kotlin)
L'applicazione Android agisce come **Peripheral / GATT Server**.
* **Motore BLE in Background:** Sfrutta un *Foreground Service* per mantenere l'antenna attiva e in ascolto, sopravvivendo ai cicli di Doze di Android.
* **Interceptor:** Usa un `NotificationListenerService` per estrarre il payload delle notifiche e inviarle via Bluetooth in un formato delimitato da caratteri Unicode invisibili.
* **MacroDroid Bridge:** Per aggirare i blocchi di sicurezza di Android 14 sulle API di sistema senza perdere l'accelerazione hardware, delega l'accensione/spegnimento dell'Hotspot a MacroDroid tramite `Intent Broadcast` mirati (`it.luigi.macsync.HOTSPOT_ON` / `OFF`).

### 💻 2. macOS Client (Swift)
L'applicazione macOS agisce come **Central** e risiede comodamente nella Menu Bar.
* **CoreBluetooth & Combine:** Gestisce la connessione BLE in modo reattivo, agganciandosi automaticamente al dispositivo memorizzato nella cache di sistema per riconnessioni istantanee al risveglio dallo stop.
* **Gestione UI:** Decodifica il payload della telemetria aggiornando in tempo reale l'interfaccia utente (es. cambi di rete, accensione fisica dell'hotspot da telefono).
* **Gestione File:** Genera e salva localmente le icone delle app Android ricevute via Bluetooth per allegarle in modo nativo al `UNUserNotificationCenter` di macOS.

---

## 📡 Protocollo BLE & UUIDs

Per garantire una comunicazione sicura e univoca, il progetto utilizza i seguenti UUID immutabili:

* **Service Principale:** `E20A39F4-73F5-4BC4-A12F-17D1AD07A961`
* **Canale Telemetria (Read/Notify):** `33333333-73F5-4BC4-A12F-17D1AD07A961`
* **Canale Notifiche (Notify):** `22222222-73F5-4BC4-A12F-17D1AD07A961`
* **Canale Comandi (Write):** `44444444-73F5-4BC4-A12F-17D1AD07A961`

*(Il pacchetto telemetrico è formattato in questo ordine: `Batteria | In Carica | Rete/SSID | Segnale | isWifi | isHotspotActive`, separati dal delimitatore `\u001F`)*

---

## 🛠 Setup e Configurazione

### Lato Android
1. Compila l'APK in modalità **Release** tramite Android Studio.
2. Concedi i permessi richiesti al primo avvio (Bluetooth, Posizione per la lettura SSID, Stato Telefono).
3. Vai nelle Impostazioni di Android -> Notifiche -> Accesso Notifiche e abilita **MacSync Notifiche**.
4. **Configurazione MacroDroid (Solo per l'Hotspot):**
   * Crea una Macro attivata dall'Intent `it.luigi.macsync.HOTSPOT_ON` con l'Azione: *Abilita Hotspot*.
   * Crea una Macro attivata dall'Intent `it.luigi.macsync.HOTSPOT_OFF` con l'Azione: *Disabilita Hotspot*.

### Lato macOS
1. Apri il progetto in Xcode e compila in modalità **Release**.
2. Al primo avvio, concedi all'app il permesso di inviare notifiche di sistema.
3. Assicurati che il Bluetooth sia acceso. L'app cercherà automaticamente il server GATT del telefono e instaurerà il collegamento.

---
*Sviluppato a scopo didattico e personale per superare i limiti di comunicazione tra ecosistemi informatici differenti.*
