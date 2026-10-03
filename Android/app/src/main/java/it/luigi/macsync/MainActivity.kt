package it.luigi.macsync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.luigi.macsync.ble.GattServerManager
import it.luigi.macsync.ui.theme.MacSyncTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var gattServerManager: GattServerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        NotificationFilter.ensureInitialized(this)
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

private fun isNotificationServiceEnabled(context: Context): Boolean {
    val pkgName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat != null && flat.contains(pkgName)
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(gattServerManager: GattServerManager) {
    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    MainScreen(gattServerManager = gattServerManager) {
        showBottomSheet = true
    }

    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            contentWindowInsets = { WindowInsets.statusBars }
        ) {
            AppSelectionScreen()
        }
    }
}

@Composable
fun MainScreen(gattServerManager: GattServerManager, onOpenNotificationsClick: () -> Unit) {
    var permissionsGranted by remember { mutableStateOf(false) }

    val statusText by gattServerManager.connectionState.collectAsState()
    val isConnected by gattServerManager.connected.collectAsState()

    val context = LocalContext.current
    val isListenerGranted = isNotificationServiceEnabled(context)
    val batteryOptimized = !isIgnoringBatteryOptimizations(context)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        
        // 1. Controlliamo SOLO i permessi vitali per far funzionare l'app
        val isBluetoothConnectGranted = permissions[Manifest.permission.BLUETOOTH_CONNECT] == true ||
                permissions[Manifest.permission.BLUETOOTH_CONNECT] == null // Fallback per versioni vecchie

        val isBluetoothAdvertiseGranted = permissions[Manifest.permission.BLUETOOTH_ADVERTISE] == true ||
                permissions[Manifest.permission.BLUETOOTH_ADVERTISE] == null

        // 2. Se il Bluetooth c'è, la UI è autorizzata a sbloccarsi
        val essentialGranted = isBluetoothConnectGranted && isBluetoothAdvertiseGranted

        permissionsGranted = essentialGranted

        // 3. Avviamo il servizio in background
        if (essentialGranted) {
            context.startForegroundService(Intent(context, MacSyncBleService::class.java))
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        )
    }

    val eggMessage = stringResource(R.string.egg_message)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(54.dp))

        Text(
            text = "MacSync",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    Toast.makeText(context, eggMessage, Toast.LENGTH_LONG).show()
                })
            }
        )

        Spacer(modifier = Modifier.height(36.dp))

        if (!permissionsGranted) {
            Text(stringResource(R.string.requesting_permissions), modifier = Modifier.padding(top = 32.dp))
            return@Column
        }

        Image(
            painter = painterResource(id = R.drawable.macbookpro),
            contentDescription = stringResource(R.string.device_label),
            modifier = Modifier.size(165.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.device_label),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isConnected) stringResource(R.string.status_connected) else stringResource(R.string.status_disconnected),
                style = MaterialTheme.typography.bodyLarge,
                color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.width(8.dp))

            if (isConnected) {
                Icon(
                    imageVector = Icons.Rounded.Link,
                    contentDescription = stringResource(R.string.cd_connected),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.LinkOff,
                    contentDescription = stringResource(R.string.cd_disconnected),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // --- SEZIONE IMPOSTAZIONI ---
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            SettingsCardItem(
                icon = Icons.Rounded.Notifications,
                title = stringResource(R.string.notifications),
                subtitle = stringResource(R.string.notifications_subtitle),
                onClick = onOpenNotificationsClick
            )

            if (!isListenerGranted) {
                Spacer(modifier = Modifier.height(8.dp))
                SettingsCardItem(
                    icon = Icons.Rounded.Warning,
                    title = stringResource(R.string.notification_access),
                    subtitle = stringResource(R.string.notification_access_subtitle),
                    isWarning = true,
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                )
            }

            if (batteryOptimized) {
                Spacer(modifier = Modifier.height(8.dp))
                SettingsCardItem(
                    icon = Icons.Rounded.Warning,
                    title = stringResource(R.string.battery_opt_title),
                    subtitle = stringResource(R.string.battery_opt_subtitle),
                    isWarning = true,
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (e: Exception) {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            AuthMethodCard(context)
        }

        Spacer(modifier = Modifier.height(28.dp))
        AboutCard(context)
        Spacer(modifier = Modifier.height(32.dp))
    }
}

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

/** Opens a URL in the default browser (or an app that handles the scheme). */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
    }
}

/**
 * Collapsible "Authorization method" card: pick Root or Shizuku. Collapsed it is
 * one row (icon + title + current method); expanded it shows the two options and
 * the Shizuku status / official install link. Uses only theme colors, so it
 * follows the light/dark scheme.
 */
@Composable
fun AuthMethodCard(context: Context) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var methodName by rememberSaveable { mutableStateOf(PrivilegeManager.getMethod(context).name) }
    var rootAvailable by remember { mutableStateOf(false) }
    var shizukuInstalled by remember { mutableStateOf(false) }
    var shizukuAlive by remember { mutableStateOf(false) }
    var shizukuGranted by remember { mutableStateOf(false) }

    val method = PrivilegeManager.Method.valueOf(methodName)

    LaunchedEffect(expanded) {
        if (expanded) {
            // `su` can be slow / prompt, so check it once per open (off main).
            rootAvailable = withContext(Dispatchers.IO) { PrivilegeManager.isRootAvailable() }
            while (true) {
                val s = withContext(Dispatchers.IO) {
                    Triple(
                        PrivilegeManager.isShizukuInstalled(context),
                        PrivilegeManager.isShizukuBinderAlive(),
                        PrivilegeManager.isShizukuPermissionGranted()
                    )
                }
                shizukuInstalled = s.first
                shizukuAlive = s.second
                shizukuGranted = s.third
                delay(1500)
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.AdminPanelSettings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.auth_method_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (method == PrivilegeManager.Method.ROOT)
                            stringResource(R.string.auth_method_root_short)
                        else stringResource(R.string.auth_method_shizuku_short),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.cd_collapse else R.string.cd_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(220)),
                exit = shrinkVertically(animationSpec = tween(220)) + fadeOut(animationSpec = tween(160))
            ) {
                Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 16.dp)) {
                    AuthOptionRow(
                        selected = method == PrivilegeManager.Method.ROOT,
                        title = stringResource(R.string.auth_root_title),
                        desc = stringResource(R.string.auth_root_desc),
                        statusText = stringResource(
                            if (rootAvailable) R.string.auth_status_available
                            else R.string.auth_status_unavailable
                        ),
                        statusOk = rootAvailable,
                        onSelect = {
                            PrivilegeManager.setMethod(context, PrivilegeManager.Method.ROOT)
                            methodName = PrivilegeManager.Method.ROOT.name
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    AuthOptionRow(
                        selected = method == PrivilegeManager.Method.SHIZUKU,
                        title = stringResource(R.string.auth_shizuku_title),
                        desc = stringResource(R.string.auth_shizuku_desc),
                        statusText = shizukuStatusText(shizukuInstalled, shizukuAlive, shizukuGranted),
                        statusOk = shizukuInstalled && shizukuAlive && shizukuGranted,
                        onSelect = {
                            PrivilegeManager.setMethod(context, PrivilegeManager.Method.SHIZUKU)
                            methodName = PrivilegeManager.Method.SHIZUKU.name
                            if (shizukuInstalled && shizukuAlive && !shizukuGranted) {
                                PrivilegeManager.requestShizukuPermission(1001)
                            }
                        }
                    )

                    Column(modifier = Modifier.padding(start = 44.dp, top = 4.dp)) {
                        Text(
                            text = stringResource(R.string.auth_shizuku_install_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.auth_shizuku_official),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickable { openUrl(context, PrivilegeManager.SHIZUKU_URL) }
                        )
                        if (shizukuInstalled && shizukuAlive && !shizukuGranted) {
                            TextButton(
                                onClick = { PrivilegeManager.requestShizukuPermission(1001) },
                                contentPadding = PaddingValues(horizontal = 0.dp)
                            ) {
                                Text(stringResource(R.string.auth_shizuku_grant))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun shizukuStatusText(installed: Boolean, alive: Boolean, granted: Boolean): String = when {
    !installed -> stringResource(R.string.auth_shizuku_not_installed)
    !alive -> stringResource(R.string.auth_shizuku_not_running)
    !granted -> stringResource(R.string.auth_shizuku_denied)
    else -> stringResource(R.string.auth_shizuku_granted)
}

@Composable
private fun AuthOptionRow(
    selected: Boolean,
    title: String,
    desc: String,
    statusText: String,
    statusOk: Boolean,
    onSelect: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusOk) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** "About" card: short description, version and a link to the GitHub repo. */
@Composable
fun AboutCard(context: Context) {
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }
    val repoUrl = "https://github.com/nexor-lab/PixelMacSync"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(18.dp))
                Text(
                    text = stringResource(R.string.about_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.about_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.about_version, version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { openUrl(context, repoUrl) }
                    .padding(vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.about_repo) + " · nexor-lab/PixelMacSync",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
