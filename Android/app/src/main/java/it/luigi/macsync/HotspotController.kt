package it.luigi.macsync

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Controls the real system Wi-Fi hotspot through root.
 *
 * Android 15 / HyperOS 3 audit result: the TetheringManager has no shell command,
 * and `cmd tethering` has no implementation. The stable root-accessible surface is
 * `cmd wifi start-softap` / `cmd wifi stop-softap` (verified on device). It starts
 * a real SoftAP and does NOT overwrite the user's saved hotspot config.
 *
 * Credentials: we reuse the device's saved SoftAP configuration via the public
 * WifiManager API, so nothing is hardcoded and nothing is logged / sent over BLE.
 * If the config is unreadable we fall back to an app-private generated profile.
 *
 * NOTE (documented limitation): `start-softap` raises the AP; internet NAT is
 * decided by the Tethering framework. On this device the AP is real (verifiable),
 * and internet sharing depends on the device's bridged-AP-with-STA support.
 */
object HotspotController {

    private const val TAG = "MacSync"
    private const val PREFS = "MacSync_Prefs"
    private const val KEY_SSID = "hotspot_ssid"
    private const val KEY_PASS = "hotspot_pass"
    private const val ROOT_TIMEOUT_S = 10L

    /** Root is available if `su -c id` returns uid=0. */
    fun isRootAvailable(): Boolean = runRoot("id").contains("uid=0")

    /** Enables the real SoftAP. Returns true only if the command reported success. */
    fun enable(context: Context): Boolean {
        val (ssid, pass) = savedProfile(context)
        val cmd = "cmd wifi start-softap \"$ssid\" wpa2 \"$pass\""
        val out = runRoot(cmd)
        val ok = out.contains("enabled successfully", true) ||
                 out.contains("SAP is enabled", true)
        if (!ok) Log.w(TAG, "start-softap failed: ${out.take(160)}")
        return ok
    }

    /** Disables the SoftAP. */
    fun disable(): Boolean {
        val out = runRoot("cmd wifi stop-softap")
        val ok = out.contains("stopped successfully", true) ||
                 out.contains("Soft AP stopped", true)
        if (!ok) Log.w(TAG, "stop-softap failed: ${out.take(160)}")
        return ok
    }

    /**
     * Reads the device's saved SoftAP SSID + passphrase. Never logs the passphrase.
     * Falls back to an app-private generated profile if the system config is unreadable.
     */
    private fun savedProfile(context: Context): Pair<String, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // 1. Best: the device's saved SoftAP SSID + passphrase (privileged API).
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val cfg = wm.javaClass.getMethod("getSoftApConfiguration").invoke(wm)
            if (cfg != null) {
                val cls = cfg.javaClass
                val ssid = cls.getMethod("getSsid").invoke(cfg) as? String
                val pass = cls.getMethod("getPassphrase").invoke(cfg) as? String
                if (!ssid.isNullOrBlank() && !pass.isNullOrBlank()) {
                    return ssid to pass
                }
            }
        } catch (e: Exception) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            Log.w(TAG, "softApConfiguration unavailable: ${cause.javaClass.simpleName}: ${cause.message}")
        }

        // 2. Reuse the system's saved AP SSID (root-readable) so the network name
        //    stays stable and matches what the phone normally broadcasts.
        var ssid = systemApSsid()
        if (ssid.isNullOrBlank()) {
            ssid = prefs.getString(KEY_SSID, null)
        }
        if (ssid.isNullOrBlank()) {
            ssid = "MacSync Hotspot"
        }
        prefs.edit().putString(KEY_SSID, ssid).apply()

        // 3. Passphrase: app-private (never logged, never sent over BLE).
        var pass = prefs.getString(KEY_PASS, null)
        if (pass.isNullOrBlank()) {
            pass = java.math.BigInteger(96, java.security.SecureRandom()).toString(32)
            prefs.edit().putString(KEY_PASS, pass).apply()
        }
        return ssid to pass
    }

    /** Reads the system's saved SoftAP SSID via root (no credentials). */
    private fun systemApSsid(): String? {
        val out = runRoot("dumpsys wifi | grep -m1 CMD_UPDATE_AP_CONFIG | grep -oE 'ssid = \"[^\"]+\"'")
        val marker = "ssid = \""
        val start = out.indexOf(marker)
        if (start < 0) return null
        val from = out.indexOf('"', start + marker.length - 1) + 1
        val end = out.indexOf('"', from)
        if (from <= 0 || end <= from) return null
        return out.substring(from, end)
    }

    /** SSID/passphrase the user must save once on macOS. Passphrase is NOT logged. */
    fun profileForDisplay(context: Context): Pair<String, String> = savedProfile(context)

    private fun runRoot(command: String): String {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(ROOT_TIMEOUT_S, TimeUnit.SECONDS)) {
                process.destroy()
                ""
            } else {
                output
            }
        } catch (e: Exception) {
            Log.e(TAG, "runRoot error: ${e.javaClass.simpleName}")
            ""
        }
    }
}
