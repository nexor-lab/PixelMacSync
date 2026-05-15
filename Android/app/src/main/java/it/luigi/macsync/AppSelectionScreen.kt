package it.luigi.macsync

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
// Import per le forme avanzate di Material Design 3
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import androidx.graphics.shapes.CornerRounding

// Classe necessaria per far digerire le forme avanzate a Jetpack Compose
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

    // Rotazione fluida
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "rotation"
    )

    // NOVITÀ: Usiamo i Keyframes per creare le "pause" di visualizzazione!
    val morphProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200 // Durata per una singola "andata"

                0f at 0 // Inizia dal biscotto
                0f at 200 with FastOutSlowInEasing // Resta un biscotto perfetto per i primi 200ms
                1f at 1000 // Usa i successivi 800ms per fondersi nel sole
                1f at 1200 // Resta un sole perfetto per gli ultimi 200ms
            },
            repeatMode = RepeatMode.Reverse // Torna indietro dolcemente
        ), label = "morph"
    )

    // Le nostre due forme preferite
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSelectionScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("MacSync_Prefs", Context.MODE_PRIVATE)

    var appList by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    var searchQuery by remember { mutableStateOf("") }
    var activeSearch by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val savedApps = prefs.getStringSet("enabled_apps", emptySet()) ?: emptySet()

            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { appInfo ->
                    (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || appInfo.packageName == "com.google.android.apps.messaging"
                }
                .map { appInfo ->
                    val iconBitmap = pm.getApplicationIcon(appInfo).toBitmap(128, 128).asImageBitmap()

                    AppItem(
                        name = pm.getApplicationLabel(appInfo).toString(),
                        packageName = appInfo.packageName,
                        isEnabled = savedApps.contains(appInfo.packageName),
                        icon = iconBitmap
                    )
                }
                .sortedBy { it.name }

            withContext(Dispatchers.Main) {
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

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = { activeSearch = false },
                active = activeSearch,
                onActiveChange = { activeSearch = it },
                placeholder = { Text("Cerca app...") },
                leadingIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Cancella")
                        }
                    } else {
                        Icon(Icons.Default.Search, contentDescription = "Cerca")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .statusBarsPadding()
            ) {}
        }
    ) { innerPadding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                MorphingLoadingIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(filteredList, key = { it.packageName }) { appItem ->
                    AppRow(appItem = appItem) { isChecked ->
                        appList = appList.map {
                            if (it.packageName == appItem.packageName) it.copy(isEnabled = isChecked) else it
                        }

                        val editor = prefs.edit()
                        val currentSaved = prefs.getStringSet("enabled_apps", mutableSetOf())?.toMutableSet() ?: mutableSetOf()

                        if (isChecked) {
                            currentSaved.add(appItem.packageName)
                        } else {
                            currentSaved.remove(appItem.packageName)
                        }

                        editor.putStringSet("enabled_apps", currentSaved)
                        editor.apply()
                    }
                }
                item {
                    Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
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
            contentDescription = "Icona di ${appItem.name}",
            modifier = Modifier.size(48.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(text = appItem.name, style = MaterialTheme.typography.bodyLarge)
            Text(text = appItem.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Switch(
            checked = appItem.isEnabled,
            onCheckedChange = null
        )
    }
}