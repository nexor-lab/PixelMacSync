package it.luigi.macsync

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import it.luigi.macsync.ble.GattServerManager
import it.luigi.macsync.ble.BLEAdvertiser
import it.luigi.macsync.ui.theme.MacSyncTheme

class MainActivity : ComponentActivity() {

    private lateinit var gattServerManager: GattServerManager
    private lateinit var bleAdvertiser: BLEAdvertiser

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inizializziamo i nostri due manager
        gattServerManager = GattServerManager(this)
        bleAdvertiser = BLEAdvertiser(this)

        setContent {
            MacSyncTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Passiamo entrambi alla UI
                    MainScreen(gattServerManager, bleAdvertiser)
                }
            }
        }
    }
}

@Composable
fun MainScreen(gattServerManager: GattServerManager, bleAdvertiser: BLEAdvertiser) {
    var permissionsGranted by remember { mutableStateOf(false) }

    // 1. NOVITÀ: Ascoltiamo in tempo reale lo stato della connessione dal manager!
    val statusText by gattServerManager.connectionState.collectAsState()

    // Questo è il launcher che fa comparire il popup di sistema
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions: Map<String, Boolean> ->
        val allGranted = permissions.entries.all { it.value }
        permissionsGranted = allGranted

        // Se l'utente ha detto sì, accendiamo tutto!
        if (allGranted) {
            gattServerManager.startServer()
            bleAdvertiser.startAdvertising()
        }
    }

    // Appena la schermata viene disegnata, lanciamo la richiesta
    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        )
    }

    // Interfaccia utente ultra-minimale
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (permissionsGranted) {
            Text(text = "Server BLE Attivo! 🚀")

            // 2. NOVITÀ: Qui usiamo la variabile reattiva invece del testo fisso
            Text(text = statusText)
        } else {
            Text(text = "Richiesta permessi Bluetooth in corso...")
        }
    }
}