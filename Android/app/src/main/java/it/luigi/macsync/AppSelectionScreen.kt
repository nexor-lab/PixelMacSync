package it.luigi.macsync

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape // <-- AGGIUNTO IMPORT MANCANTE
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import androidx.graphics.shapes.CornerRounding
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class MorphShape(
    private val morph: Morph,
    private val progress: Float
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val androidPath = morph.toPath(progress)
        val matrix = android.graphics.Matrix()
        matrix.setScale(size.width / 2f, size.height / 2f)
        matrix.postTranslate(size.width / 2f, size.height / 2f)
        androidPath.transform(matrix)
        return Outline.Generic(androidPath.asComposePath())
    }
}

@Composable
fun MorphingLoadingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "morphing")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "rotation"
    )

    val morphProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0f at 0
                // Corretto "with" in "using" per la nuova sintassi di Compose
                0f at 200 using FastOutSlowInEasing
                1f at 1000
                1f at 1200
            },
            repeatMode = RepeatMode.Reverse
        ), label = "morph"
    )

    val cookie = remember { RoundedPolygon.star(numVerticesPerRadius = 4, innerRadius = 0.5f, rounding = CornerRounding(radius = 0.2f)) }
    val sunny = remember { RoundedPolygon.star(numVerticesPerRadius = 8, innerRadius = 0.7f, rounding = CornerRounding(radius = 0.15f)) }
    val morph = remember { Morph(cookie, sunny) }

    Box(
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer {
                rotationZ = rotation
            }
            .clip(MorphShape(morph, morphProgress))
            .background(MaterialTheme.colorScheme.primary)
    )
}

data class AppItem(val name: String, val packageName: String, val isEnabled: Boolean, val icon: ImageBitmap)

private object AppSelectionCache {
    @Volatile
    var apps: List<AppItem>? = null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSelectionScreen(embedded: Boolean = false) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("MacSync_Prefs", Context.MODE_PRIVATE)

    var appList by remember { mutableStateOf(AppSelectionCache.apps.orEmpty()) }
    var isLoading by remember { mutableStateOf(AppSelectionCache.apps == null) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val savedApps = prefs.getStringSet(NotificationFilter.KEY_ENABLED_APPS, emptySet())
            ?.toSet()
            .orEmpty()
        val cachedApps = AppSelectionCache.apps
        if (cachedApps != null) {
            // Icons and labels are cached, but selection is always restored from
            // persistent preferences so leaving and reopening this tab cannot
            // resurrect stale switch positions.
            val syncedApps = cachedApps.map {
                it.copy(isEnabled = savedApps.contains(it.packageName))
            }
            AppSelectionCache.apps = syncedApps
            appList = syncedApps
            isLoading = false
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            val pm = context.packageManager

            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
            intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)

            val resolveInfos = pm.queryIntentActivities(intent, 0)
            val gate = Semaphore(8)
            val apps = coroutineScope {
                resolveInfos.map { resolveInfo ->
                    async {
                        gate.withPermit {
                            val appInfo = resolveInfo.activityInfo.applicationInfo
                            if (appInfo.packageName == context.packageName) {
                                null
                            } else {
                                AppItem(
                                    name = pm.getApplicationLabel(appInfo).toString(),
                                    packageName = appInfo.packageName,
                                    isEnabled = savedApps.contains(appInfo.packageName),
                                    icon = pm.getApplicationIcon(appInfo)
                                        .toBitmap(128, 128)
                                        .asImageBitmap(),
                                )
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
                .distinctBy { it.packageName }
                .sortedWith(compareByDescending<AppItem> { it.isEnabled }.thenBy { it.name })

            withContext(Dispatchers.Main) {
                AppSelectionCache.apps = apps
                appList = apps
                isLoading = false
            }
        }
    }

    val filteredList = if (searchQuery.isEmpty()) {
        appList
    } else {
        appList.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    val onToggle: (AppItem, Boolean) -> Unit = { appItem, isChecked ->
        val updatedApps = appList.map {
            if (it.packageName == appItem.packageName) it.copy(isEnabled = isChecked) else it
        }
        appList = updatedApps
        AppSelectionCache.apps = updatedApps

        val currentSaved = (
            prefs.getStringSet(NotificationFilter.KEY_ENABLED_APPS, emptySet())
                ?: emptySet()
            ).toMutableSet()
        if (isChecked) currentSaved.add(appItem.packageName)
        else currentSaved.remove(appItem.packageName)
        prefs.edit()
            .putStringSet(NotificationFilter.KEY_ENABLED_APPS, currentSaved)
            .apply()
    }

    if (embedded) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppSelectionHeader(searchQuery) { searchQuery = it }
            AppSelectionList(
                items = filteredList,
                isLoading = isLoading,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                onToggle = onToggle,
            )
        }
    } else {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = { AppSelectionHeader(searchQuery) { searchQuery = it } },
        ) { innerPadding ->
            AppSelectionList(
                items = filteredList,
                isLoading = isLoading,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onToggle = onToggle,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSelectionHeader(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.app_notifications_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
        )
        TextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text(stringResource(R.string.search)) },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear))
                    }
                }
            },
            shape = RoundedCornerShape(50),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun AppSelectionList(
    items: List<AppItem>,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    onToggle: (AppItem, Boolean) -> Unit,
) {
    if (isLoading) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            MorphingLoadingIndicator()
        }
    } else {
        LazyColumn(modifier = modifier) {
            items(items, key = { it.packageName }) { appItem ->
                AppRow(appItem = appItem) { isChecked ->
                    onToggle(appItem, isChecked)
                }
            }
            item {
                Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

@Composable
fun AppRow(appItem: AppItem, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = appItem.isEnabled,
                onValueChange = { onCheckedChange(it) }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            bitmap = appItem.icon,
            contentDescription = stringResource(R.string.icon_of, appItem.name),
            modifier = Modifier.size(48.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(text = appItem.name, style = MaterialTheme.typography.bodyLarge)
        }

        Switch(
            checked = appItem.isEnabled,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedIconColor = MaterialTheme.colorScheme.primary,
                uncheckedIconColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            thumbContent = if (appItem.isEnabled) {
                {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize)
                    )
                }
            } else {
                {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize)
                    )
                }
            }
        )
    }
}
