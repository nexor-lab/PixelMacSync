package it.luigi.macsync

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
// NOVITÀ: Importiamo i coroutines dispatcher per il background
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.selection.toggleable

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
        // NOVITÀ: Spostiamo tutto il calcolo pesante fuori dal Main Thread della UI!
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val savedApps = prefs.getStringSet("enabled_apps", emptySet()) ?: emptySet()

            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { appInfo ->
                    (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || appInfo.packageName == "com.google.android.apps.messaging"
                }
                .map { appInfo ->
                    // Questa conversione prima bloccava l'app, ora è in background
                    val iconBitmap = pm.getApplicationIcon(appInfo).toBitmap(128, 128).asImageBitmap()

                    AppItem(
                        name = pm.getApplicationLabel(appInfo).toString(),
                        packageName = appInfo.packageName,
                        isEnabled = savedApps.contains(appInfo.packageName),
                        icon = iconBitmap
                    )
                }
                .sortedBy { it.name }

            // Torniamo sul thread principale solo per aggiornare la lista
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
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // Ora l'animazione di caricamento sarà fluidissima fin dal primo millisecondo
                CircularProgressIndicator()
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
            // NOVITÀ: Tutta la riga è cliccabile e innesca l'interruttore!
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
            onCheckedChange = null // Messo a null perché ora il click è gestito dalla Row!
        )
    }
}