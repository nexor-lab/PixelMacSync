package it.luigi.macsync.ble

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import it.luigi.macsync.IHotspotService // L'interfaccia AIDL che hai creato prima
import rikka.shizuku.Shizuku

object ShizukuHelper {

    private const val TAG = "MacSync"
    private const val REQUEST_CODE = 1

    private var pendingPermissionCallback: ((Boolean) -> Unit)? = null

    // 1. Riferimento al nostro Servizio Privilegiato
    private var hotspotService: IHotspotService? = null

    // 2. Configurazione: diciamo a Shizuku QUALE classe caricare nel sistema
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("it.luigi.macsync", "it.luigi.macsync.HotspotUserService")
    )
        .daemon(false)
        .processNameSuffix("hotspot")
        .debuggable(true)
        .version(1)

    // 3. Il "Ponte" di comunicazione tra la nostra app e il processo di sistema
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
            hotspotService = IHotspotService.Stub.asInterface(binder)
            Log.d(TAG, "✅ Connesso ai poteri di sistema di Shizuku!")
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            hotspotService = null
            Log.e(TAG, "❌ Servizio Shizuku disconnesso.")
        }
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                Log.d(TAG, "Shizuku permission result: $granted")

                // Se i permessi sono accordati, agganciamo subito il servizio!
                if (granted) bindShizukuService()

                pendingPermissionCallback?.invoke(granted)
                pendingPermissionCallback = null
            }
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        if (hasPermission()) {
            bindShizukuService()
        } else {
            pendingPermissionCallback?.let { doRequestPermission() }
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died")
        hotspotService = null
        pendingPermissionCallback?.invoke(false)
        pendingPermissionCallback = null
    }

    fun init() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        Log.d(TAG, "ShizukuHelper initialized")

        // Se l'app si avvia e ha già i permessi, eseguiamo subito il binding in background
        if (isShizukuAvailable() && hasPermission()) {
            bindShizukuService()
        }
    }

    fun dispose() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)

        // Pulizia del servizio quando l'app muore
        if (hotspotService != null) {
            try {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            } catch (e: Exception) {
                Log.e(TAG, "Errore unbind: ${e.message}")
            }
            hotspotService = null
        }
        pendingPermissionCallback = null
    }

    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.e(TAG, "hasPermission error: ${e.message}")
            false
        }
    }

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.checkSelfPermission()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku non disponibile: ${e.message}")
            false
        }
    }

    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (hasPermission()) {
            Log.d(TAG, "Permission already granted")
            bindShizukuService()
            onResult(true)
            return
        }
        if (!isShizukuAvailable()) {
            pendingPermissionCallback = onResult
            return
        }
        pendingPermissionCallback = onResult
        doRequestPermission()
    }

    private fun doRequestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission failed: ${e.message}", e)
            pendingPermissionCallback?.invoke(false)
            pendingPermissionCallback = null
        }
    }

    // Funzione interna per avviare il servizio AIDL
    private fun bindShizukuService() {
        if (hotspotService != null) return // Evita doppi agganci
        try {
            Log.d(TAG, "Avvio binding con il servizio Shizuku...")
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (e: Exception) {
            Log.e(TAG, "Errore bindUserService: ${e.message}")
        }
    }

    // QUESTA FIRMA DEVE RIMANERE UGUALE PER NON ROMPERE IL GATT SERVER!
    fun toggleHotspot(enable: Boolean, context: Context) {
        if (!hasPermission()) {
            Log.e(TAG, "toggleHotspot: permesso Shizuku mancante")
            return
        }

        // Se per qualche motivo il servizio si è staccato, riproviamo l'aggancio
        if (hotspotService == null) {
            Log.w(TAG, "Servizio Shizuku non connesso! Tento la riconnessione...")
            bindShizukuService()
        }

        try {
            // Eseguiamo il comando pulito tramite AIDL!
            hotspotService?.toggleHotspot(enable)
            Log.d(TAG, "Inviato comando toggleHotspot($enable) al servizio Shizuku")
        } catch (e: Exception) {
            Log.e(TAG, "Errore chiamata AIDL: ${e.message}")
        }
    }
}