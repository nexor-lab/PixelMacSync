package it.luigi.macsync

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Data class di supporto per la lista
data class AppItem(val name: String, val packageName: String, var isEnabled: Boolean)

@Composable
fun AppSelectionScreen() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("MacSync_Prefs", Context.MODE_PRIVATE)

    // Stato per la lista delle app
    var appList by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Caricamento delle app in background (per non bloccare la UI)
    LaunchedEffect(Unit) {
        val pm = context.packageManager
        // Otteniamo la lista salvata attualmente
        val savedApps = prefs.getStringSet("enabled_apps", emptySet()) ?: emptySet()

        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { appInfo ->
                // Escludiamo le app di sistema per pulizia (teniamo solo quelle utente)
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || appInfo.packageName == "com.google.android.apps.messaging"
            }
            .map { appInfo ->
                AppItem(
                    name = pm.getApplicationLabel(appInfo).toString(),
                    packageName = appInfo.packageName,
                    isEnabled = savedApps.contains(appInfo.packageName)
                )
            }
            .sortedBy { it.name } // Ordine alfabetico

        appList = apps
        isLoading = false
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(appList, key = { it.packageName }) { appItem ->
                AppRow(appItem = appItem) { isChecked ->
                    // Aggiorniamo la UI
                    appList = appList.map {
                        if (it.packageName == appItem.packageName) it.copy(isEnabled = isChecked) else it
                    }

                    // Salviamo istantaneamente nelle SharedPreferences
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

@Composable
fun AppRow(appItem: AppItem, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
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