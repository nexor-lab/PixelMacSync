package it.luigi.macsync.ble

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

object ShizukuHelper {

    private const val TAG = "MacSync"
    private const val REQUEST_CODE = 1
    private const val TETHERING_WIFI = 0

    private var pendingPermissionCallback: ((Boolean) -> Unit)? = null

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                Log.d(TAG, "Shizuku permission result: $granted")
                pendingPermissionCallback?.invoke(granted)
                pendingPermissionCallback = null
            }
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        pendingPermissionCallback?.let { doRequestPermission() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died")
        pendingPermissionCallback?.invoke(false)
        pendingPermissionCallback = null
    }

    fun init() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        Log.d(TAG, "ShizukuHelper initialized")
    }

    fun dispose() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
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

    /**
     * Implementazione minimale di IIntResultListener come Binder anonimo.
     * Android 16 richiede un listener reale — non accetta null.
     * Transaction code 1 = onResult(int resultCode)
     */
    private fun createIntResultListener(onResult: (Int) -> Unit): IBinder {
        return object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                return when (code) {
                    1 -> { // IIntResultListener.onResult(int)
                        data.enforceInterface("android.net.IIntResultListener")
                        val resultCode = data.readInt()
                        Log.d(TAG, "IIntResultListener.onResult($resultCode) — 0=SUCCESS")
                        onResult(resultCode)
                        true
                    }
                    else -> super.onTransact(code, data, reply, flags)
                }
            }
        }
    }

    fun toggleHotspot(enable: Boolean, context: Context) {
        if (!hasPermission()) {
            Log.e(TAG, "toggleHotspot: permesso Shizuku mancante")
            return
        }

        Thread {
            try {
                val rawBinder = SystemServiceHelper.getSystemService("tethering")
                    ?: run {
                        Log.e(TAG, "Binder tethering non trovato")
                        return@Thread
                    }

                val binder = ShizukuBinderWrapper(rawBinder)
                Log.d(TAG, "Tethering binder descriptor: ${binder.interfaceDescriptor}")

                if (enable) {
                    startTetheringViaParcel(binder)
                } else {
                    stopTetheringViaParcel(binder)
                }

            } catch (e: Exception) {
                Log.e(TAG, "toggleHotspot error: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }.start()
    }

    private fun startTetheringViaParcel(binder: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()

        // Listener che riceve il risultato dell'operazione
        val listener = createIntResultListener { code ->
            if (code == 0) {
                Log.d(TAG, "Hotspot ON: avviato con successo")
            } else {
                Log.e(TAG, "Hotspot ON fallito con codice: $code")
            }
        }

        try {
            data.writeInterfaceToken("android.net.ITetheringConnector")

            // 1. Diciamo al sistema che il TetheringRequestParcel NON è nullo
            data.writeInt(1)

            // 2. STABLE AIDL: Salviamo la posizione e mettiamo un placeholder per la dimensione
            val startPos = data.dataPosition()
            data.writeInt(0) // Spazio vuoto che riempiremo tra poco

            // 3. Scriviamo i campi del Parcelable
            data.writeInt(TETHERING_WIFI)    // tetheringType
            data.writeInt(0)                 // localIPv4Address = null
            data.writeInt(0)                 // staticIpv4ClientAddress = null
            data.writeInt(0)                 // exemptFromEntitlementCheck = false
            data.writeInt(1)                 // showProvisioningUi = true
            data.writeInt(0)                 // connectivityScope = GLOBAL
            data.writeInt(0)                 // softApConfig = null (Aggiunto per stabilità)

            // 4. Magia Nera: Calcoliamo la dimensione totale e andiamo a sovrascrivere il placeholder
            val endPos = data.dataPosition()
            data.setDataPosition(startPos)
            data.writeInt(endPos - startPos) // Scriviamo la vera dimensione in byte!
            data.setDataPosition(endPos)     // Riportiamo il cursore in fondo

            // 5. Ora scriviamo gli argomenti della funzione MASCHERANDO L'IDENTITÀ
            data.writeString("com.android.shell") // <-- IDENTITÀ FAKE: Siamo Shizuku!
            data.writeString(null)           // attributionTag
            data.writeStrongBinder(listener)

            val result = binder.transact(4, data, reply, 0)
            reply.readException() // Se tutto è allineato, qui passerà liscio
            Log.d(TAG, "startTethering transact ok, result=$result")

        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun stopTetheringViaParcel(binder: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()

        val listener = createIntResultListener { code ->
            if (code == 0) {
                Log.d(TAG, "Hotspot OFF: spento con successo")
            } else {
                Log.e(TAG, "Hotspot OFF fallito con codice: $code")
            }
        }

        try {
            data.writeInterfaceToken("android.net.ITetheringConnector")

            data.writeInt(TETHERING_WIFI)    // type
            data.writeString("com.android.shell") // <-- IDENTITÀ FAKE: Siamo Shizuku!
            data.writeString(null)            // attributionTag
            data.writeStrongBinder(listener)

            // FIX APPLICATO QUI: Transazione 4 invece di 2
            val result = binder.transact(5, data, reply, 0)
            reply.readException()
            Log.d(TAG, "stopTethering transact ok, result=$result")

        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}