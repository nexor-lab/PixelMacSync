package it.luigi.macsync

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
// NOVITÀ: Import per la navigazione
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import it.luigi.macsync.ble.GattServerManager
import it.luigi.macsync.ui.theme.MacSyncTheme
import androidx.compose.animation.*
import androidx.compose.animation.core.tween

class MainActivity : ComponentActivity() {

    private lateinit var gattServerManager: GattServerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        gattServerManager = GattServerManager.getInstance(this)

        setContent {
            MacSyncTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(gattServerManager)
                }
            }
        }
    }
}

@Composable
fun AppNavigation(gattServerManager: GattServerManager) {
    // NOVITÀ: Controller di navigazione per le transizioni
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "home",
        // L'animazione quando APRI la lista (Sale dal basso e sfuma)
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up, tween(300)) + fadeIn(tween(300))
        },
        // L'animazione della Home che va in background (Si rimpicciolisce leggermente)
        exitTransition = {
            scaleOut(targetScale = 0.95f, animationSpec = tween(300)) + fadeOut(tween(300))
        },
        // L'animazione della Home quando TORNI INDIETRO (Si ringrandisce)
        popEnterTransition = {
            scaleIn(initialScale = 0.95f, animationSpec = tween(300)) + fadeIn(tween(300))
        },
        // L'animazione della lista che si CHIUDE (Scivola verso il basso)
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Down, tween(300)) + fadeOut(tween(300))
        }
    ) {
        composable("home") {
// ... [resto del codice dei composable identico]
            MainScreen(gattServerManager) {
                // Azione per aprire le impostazioni
                navController.navigate("app_selection")
            }
        }
        composable("app_selection") {
            AppSelectionScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}

@Composable
fun MainScreen(gattServerManager: GattServerManager, onNavigateToAppSelection: () -> Unit) {
    var permissionsGranted by remember { mutableStateOf(false) }
    val statusText by gattServerManager.connectionState.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions: Map<String, Boolean> ->
        val allGranted = permissions.entries.all { it.value }
        permissionsGranted = allGranted

        if (allGranted) {
            val intent = Intent(context, MacSyncBleService::class.java)
            context.startForegroundService(intent)
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        )
    }

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

            Button(onClick = onNavigateToAppSelection) { // NOVITÀ: Trigger per la navigazione
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