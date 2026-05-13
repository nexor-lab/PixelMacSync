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
import it.luigi.macsync.ui.theme.MacSyncTheme

class MainActivity : ComponentActivity() {

    private lateinit var gattServerManager: GattServerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inizializziamo il nostro manager
        gattServerManager = GattServerManager(this)

        setContent {
            MacSyncTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(gattServerManager)
                }
            }
        }
    }
}

@Composable
fun MainScreen(gattServerManager: GattServerManager) {
    var permissionsGranted by remember { mutableStateOf(false) }

    // Questo è il launcher che fa comparire il popup di sistema "Consenti a MacSync di usare il Bluetooth?"
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions: Map<String, Boolean> ->
        val allGranted = permissions.entries.all { it.value }
        permissionsGranted = allGranted

        // Se l'utente ha detto sì, accendiamo il server!
        if (allGranted) {
            gattServerManager.startServer()
        }
    }

    // Appena la schermata viene disegnata, lanciamo la richiesta
    LaunchedEffect(Unit) {
        // Avendo impostato API 36 minima, non serve più il check SDK_INT!
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        )
    }

    // Interfaccia utente ultra-minimale per ora
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (permissionsGranted) {
            Text(text = "Server BLE Attivo! 🚀")
            Text(text = "Il Pixel è visibile come MacSync.")
        } else {
            Text(text = "Richiesta permessi Bluetooth in corso...")
        }
    }
}