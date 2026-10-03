package it.luigi.macsync

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.lang.reflect.Method as ReflectMethod
import java.util.concurrent.TimeUnit

/**
 * Chooses how MacSync runs privileged shell commands: a rooted `su` shell or
 * Shizuku (privileged API without root). The choice is persisted and used by
 * [HotspotController] (and future privileged features).
 */
object PrivilegeManager {

    enum class Method { ROOT, SHIZUKU }

    private const val TAG = "MacSync"
    private const val PREFS = "MacSync_Prefs"
    private const val KEY_METHOD = "auth_method"
    private const val TIMEOUT_S = 10L

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val SHIZUKU_URL = "https://github.com/RikkaApps/Shizuku"

    // --- Selected method ---

    fun getMethod(context: Context): Method {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_METHOD, Method.ROOT.name) ?: Method.ROOT.name
        return try {
            Method.valueOf(stored)
        } catch (_: Exception) {
            Method.ROOT
        }
    }

    fun setMethod(context: Context, method: Method) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_METHOD, method.name).apply()
    }

    // --- Root ---

    /** Root is available if `su -c id` returns uid=0. */
    fun isRootAvailable(): Boolean = runRoot("id").contains("uid=0")

    // --- Shizuku state ---

    fun isShizukuInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }

    fun isShizukuBinderAlive(): Boolean =
        try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }

    fun isShizukuPermissionGranted(): Boolean =
        try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }

    /** Asks Shizuku for the shell permission (no-op if not ready / already granted). */
    fun requestShizukuPermission(requestCode: Int) {
        try {
            if (isShizukuBinderAlive() && !isShizukuPermissionGranted()) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestShizukuPermission error: ${e.javaClass.simpleName}")
        }
    }

    /** Whether the currently selected method is actually usable right now. */
    fun isCurrentMethodReady(context: Context): Boolean = when (getMethod(context)) {
        Method.ROOT -> isRootAvailable()
        Method.SHIZUKU -> isShizukuBinderAlive() && isShizukuPermissionGranted()
    }

    // --- Execution ---

    /** Runs a shell command with the currently selected method. Never logs secrets. */
    fun exec(context: Context, command: String): String =
        when (getMethod(context)) {
            Method.ROOT -> runRoot(command)
            Method.SHIZUKU -> runShizuku(command)
        }

    private fun runRoot(command: String): String = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        readWithTimeout(process)
    } catch (e: Exception) {
        Log.e(TAG, "runRoot error: ${e.javaClass.simpleName}")
        ""
    }

    private fun runShizuku(command: String): String = try {
        if (!isShizukuBinderAlive() || !isShizukuPermissionGranted()) {
            Log.w(TAG, "Shizuku non pronto (binder/permission).")
            ""
        } else {
            val process = newShizukuProcess(command)
            if (process == null) "" else readWithTimeout(process)
        }
    } catch (e: Exception) {
        Log.e(TAG, "runShizuku error: ${e.javaClass.simpleName}: ${e.message}")
        ""
    }

    /**
     * `Shizuku.newProcess` is private in API 13.1.5 (deprecated, planned removal
     * in API 14), so we call it reflectively. It is a method of our own library
     * dependency, not a platform hidden API, so reflection is allowed. A ProGuard
     * keep rule preserves it in release builds.
     */
    private val newProcessMethod: ReflectMethod? by lazy {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku.newProcess not found: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun newShizukuProcess(command: String): Process? = try {
        // `sh -c "<cmd> 2>&1"` merges stderr; ShizukuRemoteProcess has no
        // redirectErrorStream().
        newProcessMethod?.invoke(null, arrayOf("sh", "-c", "$command 2>&1"), null, null) as? Process
    } catch (e: Exception) {
        Log.e(TAG, "newProcess invoke error: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /**
     * Reads stdout in a worker thread with a timeout. We deliberately avoid
     * `Process.waitFor(timeout, unit)`: ShizukuRemoteProcess throws
     * IllegalArgumentException("process hasn't exited") instead of returning
     * false on timeout.
     */
    private fun readWithTimeout(process: Process): String {
        val output = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { stream ->
                    val buf = CharArray(4096)
                    var n = stream.read(buf)
                    while (n >= 0) {
                        output.append(buf, 0, n)
                        n = stream.read(buf)
                    }
                }
            } catch (_: Exception) {
            }
        }
        reader.start()
        reader.join(TIMEOUT_S * 1000)
        if (reader.isAlive) {
            reader.interrupt()
            process.destroy()
        }
        return output.toString()
    }
}
