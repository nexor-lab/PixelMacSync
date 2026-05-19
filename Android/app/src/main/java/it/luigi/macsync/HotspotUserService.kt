package it.luigi.macsync

import android.content.Context
import android.util.Log
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

// 1. NESSUN PARAMETRO NEL COSTRUTTORE! Shizuku lo esige vuoto per avviarlo in background.
class HotspotUserService : IHotspotService.Stub() {

    override fun toggleHotspot(enable: Boolean) {
        try {
            // 2. Magia Nera: Creiamo un "System Context" dal nulla dentro il processo shell
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            var activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            if (activityThread == null) {
                activityThread = activityThreadClass.getMethod("systemMain").invoke(null)
            }
            val context = activityThreadClass.getMethod("getSystemContext").invoke(activityThread) as Context

            // 3. Recuperiamo il manager ufficiale usando il nome del servizio
            val tetheringManager = context.getSystemService("tethering")
            val tmClass = tetheringManager.javaClass

            if (enable) {
                // Costruiamo la richiesta via Reflection
                val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
                val builder = builderClass.getConstructor(Int::class.java).newInstance(0) // 0 = TETHERING_WIFI
                val request = builderClass.getMethod("build").invoke(builder)

                val executor = Executor { it.run() }

                val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
                val callback = Proxy.newProxyInstance(
                    context.classLoader,
                    arrayOf(callbackClass)
                ) { _, method, _ ->
                    if (method.name == "onTetheringStarted") {
                        Log.d("MacSync", "Hotspot ON (Hardware Offload OK!)")
                    } else if (method.name == "onTetheringFailed") {
                        Log.e("MacSync", "Errore avvio Hotspot dal sistema")
                    }
                    null
                }

                // Eseguiamo il comando startTethering ufficiale!
                val startMethod = tmClass.getDeclaredMethod(
                    "startTethering",
                    Class.forName("android.net.TetheringManager\$TetheringRequest"),
                    Executor::class.java,
                    callbackClass
                )
                startMethod.invoke(tetheringManager, request, executor, callback)

            } else {
                // Spegniamo l'hotspot
                val stopMethod = tmClass.getDeclaredMethod("stopTethering", Int::class.java)
                stopMethod.invoke(tetheringManager, 0) // 0 = TETHERING_WIFI
                Log.d("MacSync", "Hotspot OFF")
            }
        } catch (e: Exception) {
            Log.e("MacSync", "Errore UserService: ${e.message}")
            e.printStackTrace()
        }
    }
}