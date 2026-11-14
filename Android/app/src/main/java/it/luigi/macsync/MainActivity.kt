package it.luigi.macsync

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.luigi.macsync.ble.GattServerManager
import it.luigi.macsync.unlock.AndroidKeyStore
import it.luigi.macsync.unlock.PairingActivity
import it.luigi.macsync.unlock.PairingController
import it.luigi.macsync.unlock.TrustedMacStore
import it.luigi.macsync.unlock.UnbindActivity
import it.luigi.macsync.unlock.UnlockController
import it.luigi.macsync.unlock.UnlockSettings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.ContextCompat
import it.luigi.macsync.ble.MacSyncNotificationListener
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

        PairingController.restore(this)
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

    override fun onResume() {
        super.onResume()
        // Retry a PAIR_BEGIN that could not create the biometric key while the
        // phone was locked. Safe no-op when there is no pending request.
        PairingController.retryPendingBegin(this)
        requestNotificationListenerRebindIfEnabled()
    }

    /**
     * Package updates can leave an already-authorised listener enabled in
     * Settings but not live. Ask the system to rebind when the app returns to
     * the foreground; the call is a no-op if the listener is already connected.
     */
    private fun requestNotificationListenerRebindIfEnabled() {
        if (!isNotificationServiceEnabled(this)) return
        try {
            NotificationListenerService.requestRebind(
                ComponentName(this, MacSyncNotificationListener::class.java)
            )
        } catch (e: Exception) {
            Log.w("MacSync", "Notification listener rebind failed: ${e.message}")
        }
    }
}

private fun isNotificationServiceEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        ?: return false
    val expected = ComponentName(context, MacSyncNotificationListener::class.java)
    return flat.split(':').any { value ->
        val configured = ComponentName.unflattenFromString(value)
        configured?.packageName == expected.packageName &&
            configured.className == expected.className
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(gattServerManager: GattServerManager) {
    var showContactsSheet by remember { mutableStateOf(false) }
    val contactsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    MainScreen(
        gattServerManager = gattServerManager,
        onOpenContactsClick = { showContactsSheet = true }
    )

    if (showContactsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showContactsSheet = false },
            sheetState = contactsSheetState,
            containerColor = MaterialTheme.colorScheme.background,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            contentWindowInsets = { WindowInsets.statusBars }
        ) {
            ContactSelectionSheet()
        }
    }
}

private enum class AppSection {
    HOME,
    NOTIFICATIONS,
    SETTINGS,
}

private fun essentialPermissionsGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context, Manifest.permission.BLUETOOTH_CONNECT
    ) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_ADVERTISE
        ) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

@Composable
fun MainScreen(
    gattServerManager: GattServerManager,
    onOpenContactsClick: () -> Unit
) {
    val context = LocalContext.current
    val statusText by gattServerManager.connectionState.collectAsState()
    val isConnected by gattServerManager.connected.collectAsState()
    val pendingPairing by PairingController.pendingFlow.collectAsState()
    var selectedSection by rememberSaveable { mutableStateOf(AppSection.HOME) }
    var permissionsGranted by remember {
        mutableStateOf(essentialPermissionsGranted(context))
    }
    var permissionRequestAttempted by rememberSaveable { mutableStateOf(false) }

    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val isListenerGranted = remember(resumeTick) { isNotificationServiceEnabled(context) }
    val batteryOptimized = remember(resumeTick) { !isIgnoringBatteryOptimizations(context) }

    LaunchedEffect(pendingPairing) {
        if (pendingPairing != null) selectedSection = AppSection.HOME
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionRequestAttempted = true
        val granted = essentialPermissionsGranted(context)
        permissionsGranted = granted
        if (granted) {
            context.startForegroundService(Intent(context, MacSyncBleService::class.java))
        } else {
            context.stopService(Intent(context, MacSyncBleService::class.java))
        }
    }

    LaunchedEffect(Unit) {
        val granted = essentialPermissionsGranted(context)
        permissionsGranted = granted
        if (granted) {
            context.startForegroundService(Intent(context, MacSyncBleService::class.java))
        } else {
            context.stopService(Intent(context, MacSyncBleService::class.java))
        }
    }
    LaunchedEffect(resumeTick) {
        if (resumeTick > 0) {
            val granted = essentialPermissionsGranted(context)
            permissionsGranted = granted
            if (granted) {
                context.startForegroundService(Intent(context, MacSyncBleService::class.java))
            } else {
                context.stopService(Intent(context, MacSyncBleService::class.java))
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                AppSection.entries.forEach { section ->
                    NavigationBarItem(
                        selected = selectedSection == section,
                        onClick = { selectedSection = section },
                        icon = {
                            Icon(
                                imageVector = when (section) {
                                    AppSection.HOME -> Icons.Rounded.Home
                                    AppSection.NOTIFICATIONS -> Icons.Rounded.Notifications
                                    AppSection.SETTINGS -> Icons.Rounded.Settings
                                },
                                contentDescription = null,
                            )
                        },
                        label = {
                            Text(
                                when (section) {
                                    AppSection.HOME -> stringResource(R.string.nav_home)
                                    AppSection.NOTIFICATIONS -> stringResource(R.string.nav_notifications)
                                    AppSection.SETTINGS -> stringResource(R.string.nav_settings)
                                }
                            )
                        },
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!permissionsGranted) {
                PermissionGate(
                    showSettingsAction = permissionRequestAttempted,
                    onRequest = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_ADVERTISE,
                                Manifest.permission.POST_NOTIFICATIONS,
                            )
                        )
                    },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    },
                )
            } else {
                AnimatedContent(
                    targetState = selectedSection,
                    transitionSpec = {
                        val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (
                            fadeIn(tween(180)) +
                                slideInHorizontally(tween(220)) { it / 8 * direction }
                            ).togetherWith(
                            fadeOut(tween(120)) +
                                slideOutHorizontally(tween(180)) { -it / 10 * direction }
                        )
                    },
                    label = "app-section",
                ) { section ->
                    when (section) {
                        AppSection.HOME -> HomeSection(
                            context = context,
                            gattServerManager = gattServerManager,
                            statusText = statusText,
                            isConnected = isConnected,
                            pendingPairing = pendingPairing,
                        )
                        AppSection.NOTIFICATIONS -> NotificationsSection(
                        )
                        AppSection.SETTINGS -> SettingsSection(
                            context = context,
                            isListenerGranted = isListenerGranted,
                            batteryOptimized = batteryOptimized,
                            onOpenContactsClick = onOpenContactsClick,
                        )
                    }
                }
            }

        }
    }
}

@Composable
private fun PermissionGate(
    showSettingsAction: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Icon(
                imageVector = Icons.Rounded.Bluetooth,
                contentDescription = null,
                modifier = Modifier.padding(22.dp).size(42.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.permission_gate_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.permission_gate_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequest) {
            Text(stringResource(R.string.permission_gate_action))
        }
        if (showSettingsAction) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.permission_gate_settings_action))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 18.dp),
    )
}

@Composable
private fun HomeSection(
    context: Context,
    gattServerManager: GattServerManager,
    statusText: String,
    isConnected: Boolean,
    pendingPairing: PairingController.Pending?,
) {
    val eggMessage = stringResource(R.string.egg_message)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = {
                            Toast.makeText(context, eggMessage, Toast.LENGTH_LONG).show()
                        })
                    }
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                )
            }
            ConnectionStatusPill(isConnected, statusText)
        }

        ConnectionOverviewCard(
            gattServerManager = gattServerManager,
            statusText = statusText,
            isConnected = isConnected,
        )

        pendingPairing?.let { pending ->
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                PairingRequestCard(
                    macName = pending.macName,
                    onConfirm = {
                        context.startActivity(Intent(context, PairingActivity::class.java))
                    },
                    onCancel = { PairingController.cancel(context) },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.macs_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        MacDevicesCard(gattServerManager)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ConnectionStatusPill(connected: Boolean, statusText: String) {
    val container by animateColorAsState(
        if (connected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        label = "connection-pill",
    )
    Surface(
        shape = RoundedCornerShape(50),
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (connected) Icons.Rounded.Link else Icons.Rounded.LinkOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (connected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = if (connected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ConnectionOverviewCard(
    gattServerManager: GattServerManager,
    statusText: String,
    isConnected: Boolean,
) {
    val macs by gattServerManager.savedMacs.collectAsState()
    val activeId by gattServerManager.activeMacId.collectAsState()
    val connectedId by gattServerManager.connectedMacId.collectAsState()
    val displayedId = connectedId ?: activeId
    val activeMac = macs.firstOrNull { it.id == displayedId } ?: macs.firstOrNull()
    val cardColor by animateColorAsState(
        if (isConnected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        label = "connection-card",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        shape = RoundedCornerShape(24.dp),
        color = cardColor,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.LaptopMac,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(14.dp).size(32.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.current_mac),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isConnected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = activeMac?.name ?: stringResource(R.string.macs_empty),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AnimatedContent(targetState = statusText, label = "connection-status") { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isConnected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationsSection() {
    AppSelectionScreen(embedded = true)
}

@Composable
private fun SettingsSection(
    context: Context,
    isListenerGranted: Boolean,
    batteryOptimized: Boolean,
    onOpenContactsClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SectionHeader(title = stringResource(R.string.settings))
        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!isListenerGranted) {
                SettingsCardItem(
                    icon = Icons.Rounded.Warning,
                    title = stringResource(R.string.notification_access),
                    subtitle = stringResource(R.string.notification_access_subtitle),
                    isWarning = true,
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                )
            }
            if (batteryOptimized) {
                SettingsCardItem(
                    icon = Icons.Rounded.BatteryAlert,
                    title = stringResource(R.string.battery_opt_title),
                    subtitle = stringResource(R.string.battery_opt_subtitle),
                    isWarning = true,
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (_: Exception) {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    },
                )
            }
            UnlockPolicyCard(context)
            ContactsCard(context, onOpenContactsClick)
            AuthMethodCard(context)
            LanguageCard(context)
            AboutCard(context)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun MacDevicesCard(gattServerManager: GattServerManager) {
    val macs by gattServerManager.savedMacs.collectAsState()
    val connectedId by gattServerManager.connectedMacId.collectAsState()

    // Refresh the "fingerprint unlock bound" state after pairing/unbinding.
    val context = LocalContext.current
    var trustVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) { trustVersion++ }
        }
        context.registerReceiver(
            receiver, IntentFilter(PairingController.ACTION_TRUST_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    val showUnlock = remember(trustVersion) { UnlockSettings.remoteWake(context) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        if (macs.isEmpty()) {
            Text(
                text = stringResource(R.string.macs_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
            )
        }
        macs.forEach { mac ->
            val trusted = remember(trustVersion, mac.id) {
                TrustedMacStore.get(context, mac.id)
            }
            val isBound = trusted?.trusted == true
            val unlockKeyUsable = remember(trustVersion, mac.id, trusted?.keyAlias) {
                trusted?.keyAlias?.let { AndroidKeyStore.isUnlockKeyPresent(it) } ?: false
            }
            MacDeviceRow(
                mac = mac,
                isConnected = connectedId == mac.id,
                isBound = isBound,
                unlockKeyUsable = unlockKeyUsable,
                showUnlock = showUnlock,
                onConnect = { gattServerManager.connectActiveMac(mac.id) },
                onDisconnect = { gattServerManager.disconnectActiveMac() },
                onForget = { gattServerManager.forgetMac(mac.id) },
                onBind = { PairingController.requestPairing(context, mac.id) },
                onUnbind = {
                    context.startActivity(
                        Intent(context, UnbindActivity::class.java).apply {
                            putExtra(UnbindActivity.EXTRA_MAC_ID, mac.id)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                },
                onTriggerUnlock = {
                    if (connectedId == mac.id) {
                        UnlockController.requestUnlock(context, mac.id)
                    }
                },
            )
        }
    }
}

@Composable
private fun MacDeviceRow(
    mac: SavedMac,
    isConnected: Boolean,
    isBound: Boolean,
    unlockKeyUsable: Boolean,
    showUnlock: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    onBind: () -> Unit,
    onUnbind: () -> Unit,
    onTriggerUnlock: () -> Unit
) {
    var confirmUnbind by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isConnected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LaptopMac,
                        contentDescription = null,
                        tint = if (isConnected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp).size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mac.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                if (isConnected) R.string.mac_connected else R.string.cd_disconnected
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isConnected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (isBound && showUnlock) {
                            Text(
                                text = stringResource(
                                    if (unlockKeyUsable) R.string.mac_unlock_bound
                                    else R.string.unlock_key_invalid_repair
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (unlockKeyUsable) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.more_actions),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        if (!isBound && showUnlock) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.mac_unlock_bind)) },
                                enabled = isConnected,
                                onClick = {
                                    menuExpanded = false
                                    onBind()
                                },
                            )
                        }
                        if (isBound) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.mac_unlock_unbind),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    confirmUnbind = true
                                },
                            )
                        } else {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.mac_delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    confirmForget = true
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        if (isConnected) confirmDisconnect = true else onConnect()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = if (isConnected) Icons.Rounded.LinkOff else Icons.Rounded.Link,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(
                            if (isConnected) R.string.mac_disconnect else R.string.mac_connect
                        )
                    )
                }
                if (isBound && showUnlock) {
                    Button(
                        onClick = onTriggerUnlock,
                        enabled = isConnected,
                        modifier = Modifier.weight(1.25f),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LockOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.mac_unlock_trigger))
                    }
                }
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.mac_forget_confirm_title)) },
            text = { Text(stringResource(R.string.mac_forget_confirm_text, mac.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    onForget()
                }) {
                    Text(stringResource(R.string.mac_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) {
                    Text(stringResource(R.string.unlock_prompt_cancel))
                }
            },
        )
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.mac_disconnect_confirm_title)) },
            text = { Text(stringResource(R.string.mac_disconnect_confirm_text, mac.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    onDisconnect()
                }) {
                    Text(stringResource(R.string.mac_disconnect))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) {
                    Text(stringResource(R.string.unlock_prompt_cancel))
                }
            },
        )
    }

    if (confirmUnbind) {
        AlertDialog(
            onDismissRequest = { confirmUnbind = false },
            title = { Text(stringResource(R.string.unbind_confirm_title)) },
            text = { Text(stringResource(R.string.unbind_confirm_text, mac.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnbind = false
                    onUnbind()
                }) {
                    Text(
                        text = stringResource(R.string.mac_unlock_unbind),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnbind = false }) {
                    Text(stringResource(R.string.unlock_prompt_cancel))
                }
            },
        )
    }
}

@Composable
fun UnlockPolicyCard(context: Context) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var awayLock by remember { mutableStateOf(UnlockSettings.awayAutoLock(context)) }
    var remoteWake by remember { mutableStateOf(UnlockSettings.remoteWake(context)) }

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
                    imageVector = Icons.Rounded.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.unlock_policy_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.unlock_policy_subtitle),
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
                Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.unlock_away_lock_title),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.unlock_away_lock_sub),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = awayLock, onCheckedChange = {
                            awayLock = it
                            UnlockSettings.setAwayAutoLock(context, it)
                            UnlockController.sendPolicy(context)
                        })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.unlock_remote_wake_title),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.unlock_remote_wake_sub),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = remoteWake, onCheckedChange = {
                            remoteWake = it
                            UnlockSettings.setRemoteWake(context, it)
                            UnlockController.sendPolicy(context)
                        })
                    }
                }
            }
        }
    }
}

@Composable
fun ContactsCard(context: Context, onManage: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var remoteDial by remember { mutableStateOf(ContactsRepository.remoteDialEnabled(context)) }
    var autoSync by remember { mutableStateOf(ContactsRepository.autoSync(context)) }
    var directDial by remember { mutableStateOf(ContactsRepository.directDial(context)) }
    val selectedCount = remember { ContactsRepository.selectedIds(context).size }
    val subtitle = if (selectedCount > 0)
        stringResource(R.string.contacts_selected_count, selectedCount)
    else stringResource(R.string.contacts_none)

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
                    imageVector = Icons.Rounded.Contacts,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.contacts_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
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
                Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.contacts_remote_dial),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = remoteDial,
                            onCheckedChange = {
                                remoteDial = it
                                ContactsRepository.setRemoteDialEnabled(context, it)
                                // Push the new state to the Mac so its dial UI updates.
                                GattServerManager.getInstance(context).refreshTelemetryNow()
                            }
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onManage() }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.contacts_manage),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.contacts_auto_sync),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = autoSync,
                            onCheckedChange = {
                                autoSync = it
                                ContactsRepository.setAutoSync(context, it)
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.contacts_direct_dial),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.contacts_direct_dial_sub),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = directDial,
                            onCheckedChange = {
                                directDial = it
                                ContactsRepository.setDirectDial(context, it)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PairingRequestCard(
    macName: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.pair_request_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.pair_request_text, macName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.pair_request_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.pair_cancel))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(onClick = onConfirm) {
                    Text(stringResource(R.string.pair_confirm))
                }
            }
        }
    }
}

/** Bottom settings row: system / 简体中文 / English (platform per-app locale). */
@Composable
fun LanguageCard(context: Context) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val current = AppLanguage.current(context)
    val currentLabel = when {
        current.startsWith("zh") -> stringResource(R.string.language_zh)
        current.startsWith("en") -> stringResource(R.string.language_en)
        else -> stringResource(R.string.language_system)
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
                    imageVector = Icons.Rounded.Translate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.language_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = currentLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(220)),
                exit = shrinkVertically(animationSpec = tween(220)) + fadeOut(animationSpec = tween(160))
            ) {
                Column(
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp)
                ) {
                    LanguageOption(
                        label = stringResource(R.string.language_system),
                        selected = current.isBlank()
                    ) {
                        AppLanguage.set(context, AppLanguage.SYSTEM)
                        (context as? android.app.Activity)?.recreate()
                    }
                    LanguageOption(
                        label = stringResource(R.string.language_zh),
                        selected = current.startsWith("zh")
                    ) {
                        AppLanguage.set(context, AppLanguage.ZH_CN)
                        (context as? android.app.Activity)?.recreate()
                    }
                    LanguageOption(
                        label = stringResource(R.string.language_en),
                        selected = current.startsWith("en")
                    ) {
                        AppLanguage.set(context, AppLanguage.EN)
                        (context as? android.app.Activity)?.recreate()
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
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
    var expanded by rememberSaveable { mutableStateOf(false) }
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
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.about_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.about_version, version),
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
                Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp)) {
                    Text(
                        text = stringResource(R.string.about_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
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

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = stringResource(R.string.about_crypto_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.about_crypto),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.about_unlock_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.about_unlock),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                }
            }
        }
    }
}
