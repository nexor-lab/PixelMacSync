package it.luigi.macsync

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
// NOVITÀ: Import per Coil
import coil.compose.AsyncImage

// Ora l'icona è un Drawable nativo che passiamo a Coil
data class AppItem(val name: String, val packageName: String, var isEnabled: Boolean, val icon: Drawable)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSelectionScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("MacSync_Prefs", Context.MODE_PRIVATE)

    var appList by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // NOVITÀ: Stati per la barra di ricerca
    var searchQuery by remember { mutableStateOf("") }
    var activeSearch by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val pm = context.packageManager
        val savedApps = prefs.getStringSet("enabled_apps", emptySet()) ?: emptySet()

        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { appInfo ->
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || appInfo.packageName == "com.google.android.apps.messaging"
            }
            .map { appInfo ->
                AppItem(
                    name = pm.getApplicationLabel(appInfo).toString(),
                    packageName = appInfo.packageName,
                    isEnabled = savedApps.contains(appInfo.packageName),
                    icon = pm.getApplicationIcon(appInfo) // Passiamo il Drawable crudo
                )
            }
            .sortedBy { it.name }

        appList = apps
        isLoading = false
    }

    // Filtriamo la lista in base alla ricerca
    val filteredList = if (searchQuery.isEmpty()) {
        appList
    } else {
        appList.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            // NOVITÀ: SearchBar integrata stile Pixel
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
                    .statusBarsPadding() // Non si sovrappone alla status bar
            ) {}
        }
    ) { innerPadding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding), // Applica il padding del Scaffold per la top bar
                contentPadding = WindowInsets.navigationBars.asPaddingValues() // Passa sotto la barra dei gesti in basso
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
            }
        }
    }
}

@Composable
fun AppRow(appItem: AppItem, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp), // Aumentato un po' il padding verticale
        verticalAlignment = Alignment.CenterVertically
    ) {
        // NOVITÀ: Coil renderizza l'icona nativa senza sforzo
        AsyncImage(
            model = appItem.icon,
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
            onCheckedChange = onCheckedChange
        )
    }
}