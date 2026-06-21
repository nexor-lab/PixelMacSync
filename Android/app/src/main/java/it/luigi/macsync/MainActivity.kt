package it.luigi.macsync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.luigi.macsync.ble.GattServerManager
import it.luigi.macsync.ui.theme.MacSyncTheme

class MainActivity : ComponentActivity() {

    private lateinit var gattServerManager: GattServerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
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

// Controllo nativo per verificare se il servizio di lettura notifiche è attivo nel sistema
private fun isNotificationServiceEnabled(context: Context): Boolean {
    val pkgName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat != null && flat.contains(pkgName)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(gattServerManager: GattServerManager) {
    var showBottomSheet by remember { mutableStateOf(false) }

    // FIX GLITCH: skipPartiallyExpanded = false ripristina la fisica nativa dello swipe verso l'alto
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    MainScreen(gattServerManager = gattServerManager) {
        showBottomSheet = true
    }

    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            // Rimosso il Box con altezza fissa 0.9f: ora si adatta da solo senza buggare la status bar
            AppSelectionScreen()
        }
    }
}

@Composable
fun MainScreen(gattServerManager: GattServerManager, onOpenNotificationsClick: () -> Unit) {
    var permissionsGranted by remember { mutableStateOf(false) }

    val statusText by gattServerManager.connectionState.collectAsState()
    val macInfo by gattServerManager.macState.collectAsState()

    val context = LocalContext.current
    val isListenerGranted = isNotificationServiceEnabled(context)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        permissionsGranted = allGranted
        if (allGranted) {
            context.startForegroundService(Intent(context, MacSyncBleService::class.java))
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
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(54.dp)) // Margine dalla status bar

        // --- 1. TITOLO EROE ---
        Text(
            text = "MacSync",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            ),
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(36.dp))

        if (!permissionsGranted) {
            Text("Richiesta permessi Bluetooth...", modifier = Modifier.padding(top = 32.dp))
            return@Column
        }

        // --- 2. IL TUO PNG DEL MAC ---
        Image(
            painter = painterResource(id = R.drawable.macbookpro),
            contentDescription = "MacBook Pro",
            modifier = Modifier.size(165.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Nome del Mac
        Text(
            text = macInfo?.name ?: "MacBook Pro",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(12.dp))

        // --- 3. RIGA STATO + BATTERIA ---
        val isConnected = macInfo != null && !statusText.contains("Disconnesso", ignoreCase = true)

        val cleanStatus = when {
            statusText.contains("Connesso", ignoreCase = true) -> "Connesso"
            statusText.contains("Disconnesso", ignoreCase = true) -> "Disconnesso"
            else -> statusText
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = cleanStatus,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )

            if (isConnected && macInfo != null) {
                Spacer(modifier = Modifier.width(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${macInfo!!.batteryLevel}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    MacStyleBatteryIcon(level = macInfo!!.batteryLevel, isCharging = macInfo!!.isCharging)
                }
            } else if (cleanStatus == "Disconnesso") {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Rounded.LinkOff,
                    contentDescription = "Disconnesso",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 🚀 IL FIX CHIAVE:
        // Rimosso .weight(1f), ora c'è un elegante margine fisso di 48.dp
        Spacer(modifier = Modifier.height(48.dp))

        // --- 4. SEZIONE IMPOSTAZIONI (Agganciata al centro-alto) ---
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Impostazioni",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            SettingsCardItem(
                icon = Icons.Rounded.Notifications,
                title = "Notifiche",
                subtitle = "Sincronizza notifiche con il Mac",
                onClick = onOpenNotificationsClick
            )

            if (!isListenerGranted) {
                Spacer(modifier = Modifier.height(8.dp))
                SettingsCardItem(
                    icon = Icons.Rounded.Warning,
                    title = "Accesso alle Notifiche",
                    subtitle = "Tocca per concedere il permesso di sistema",
                    isWarning = true,
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                )
            }
        }
    }
}
// Replica esatta del design della batteria di macOS (Guscio + Polo positivo + Livello interno)
@Composable
fun MacStyleBatteryIcon(level: Int, isCharging: Boolean) {
    val fillColor = if (isCharging) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier = Modifier.size(width = 22.dp, height = 11.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 1.5.dp.toPx()
            val bodyWidth = size.width * 0.88f
            val capWidth = size.width * 0.12f
            val capHeight = size.height * 0.45f
            val corner = CornerRadius(2.dp.toPx(), 2.dp.toPx())

            // 1. Guscio esterno
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(bodyWidth - stroke, size.height - stroke),
                cornerRadius = corner,
                style = Stroke(width = stroke)
            )

            // 2. Polo positivo (tappino a destra)
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(bodyWidth - (stroke / 2), (size.height - capHeight) / 2),
                size = Size(capWidth, capHeight),
                cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
            )

            // 3. Barra di riempimento interna proporzionale
            val pad = 2.dp.toPx()
            val maxFill = bodyWidth - (pad * 2)
            val actualFill = maxFill * (level / 100f)
            if (level > 0) {
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(pad, pad),
                    size = Size(actualFill, size.height - (pad * 2)),
                    cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
                )
            }
        }
    }
}

// Componente riga per la lista Impostazioni in basso
@Composable
fun SettingsCardItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isWarning: Boolean = false,
    onClick: () -> Unit
) {
    val bg = if (isWarning) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val txtColor = if (isWarning) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurface

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = bg
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = txtColor)
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = if (isWarning) txtColor.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}