package it.luigi.macsync // Assicurati che sia il tuo package!

import android.content.Context
import android.util.Log
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

class HotspotUserService(private val context: Context) : IHotspotService.Stub() {

    override fun toggleHotspot(enable: Boolean) {
        try {
            // Recuperiamo il manager ufficiale
            val tetheringManager = context.getSystemService(Context.TETHERING_SERVICE)
            val tmClass = tetheringManager.javaClass

            if (enable) {
                // 1. Creiamo la richiesta (TetheringRequest) scavalcando il compilatore
                val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
                val builder = builderClass.getConstructor(Int::class.java).newInstance(0) // 0 = TETHERING_WIFI
                val request = builderClass.getMethod("build").invoke(builder)

                // 2. Creiamo un Executor base
                val executor = Executor { it.run() }

                // 3. Iniettiamo un Callback fittizio tramite Proxy
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

                // 4. Lanciamo startTethering!
                val startMethod = tmClass.getDeclaredMethod(
                    "startTethering",
                    Class.forName("android.net.TetheringManager\$TetheringRequest"),
                    Executor::class.java,
                    callbackClass
                )
                startMethod.invoke(tetheringManager, request, executor, callback)

            } else {
                // Lanciamo stopTethering(int)
                val stopMethod = tmClass.getDeclaredMethod("stopTethering", Int::class.java)
                stopMethod.invoke(tetheringManager, 0) // 0 = TETHERING_WIFI
                Log.d("MacSync", "Hotspot OFF")
            }
        } catch (e: Exception) {
            Log.e("MacSync", "Errore Reflection: ${e.message}")
            e.printStackTrace()
        }
    }
}