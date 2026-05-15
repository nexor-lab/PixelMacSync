package it.luigi.macsync

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge // NOVITÀ: Import per l'edge-to-edge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import it.luigi.macsync.ble.GattServerManager
import it.luigi.macsync.ble.BLEAdvertiser
import it.luigi.macsync.ui.theme.MacSyncTheme

class MainActivity : ComponentActivity() {

    private lateinit var gattServerManager: GattServerManager
    private lateinit var bleAdvertiser: BLEAdvertiser

    override fun onCreate(savedInstanceState: Bundle?) {
        // NOVITÀ: Abilita le barre trasparenti prima del super.onCreate!
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        gattServerManager = GattServerManager(this)
        bleAdvertiser = BLEAdvertiser(this)

        setContent {
            MacSyncTheme {
                Surface(
                    // rimosso systemBarsPadding() da qui! La Surface ora prende tutto lo schermo al 100%
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(gattServerManager, bleAdvertiser)
                }
            }
        }
    }
}

@Composable
fun MainScreen(gattServerManager: GattServerManager, bleAdvertiser: BLEAdvertiser) {
    var permissionsGranted by remember { mutableStateOf(false) }
    var showAppSelection by remember { mutableStateOf(false) }

    val statusText by gattServerManager.connectionState.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions: Map<String, Boolean> ->
        val allGranted = permissions.entries.all { it.value }
        permissionsGranted = allGranted

        if (allGranted) {
            gattServerManager.startServer()
            bleAdvertiser.startAdvertising()
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        )
    }

    BackHandler(enabled = showAppSelection) {
        showAppSelection = false
    }

    if (showAppSelection) {
        Column(modifier = Modifier.fillMaxSize()) {
            Button(
                onClick = { showAppSelection = false },
                modifier = Modifier
                    .padding(16.dp)
                    .statusBarsPadding() // NOVITÀ: Spinge solo il bottone sotto l'orologio/notch
            ) {
                Text("← Torna alla Home")
            }
            AppSelectionScreen()
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (permissionsGranted) {
                Text(text = "Server BLE Attivo! \uD83D\uDE80", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = statusText)
                Spacer(modifier = Modifier.height(32.dp))

                Button(onClick = { showAppSelection = true }) {
                    Text("Configura App Notifiche")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }) {
                    Text("Permesso Sistema Notifiche")
                }
            } else {
                Text(text = "Richiesta permessi Bluetooth in corso...")
            }
        }
    }
}