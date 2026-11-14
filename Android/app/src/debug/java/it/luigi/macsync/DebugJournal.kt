package it.luigi.macsync

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DEBUG-ONLY on-device diagnostics store.
 *
 * Appends timestamped runtime/diagnostic lines to the app's private
 * `files/diagnostics.log` (retrievable with
 * `adb shell run-as it.luigi.macsync.debug cat files/diagnostics.log`).
 *
 * This class exists ONLY in the `debug` source set and is installed by
 * `DebugApp`; beta and release builds contain no diagnostics and never write
 * this file. No personal data is logged (numbers/bodies are never passed in).
 */
object DebugJournal {

    private const val MAX_BYTES = 256 * 1024
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun install(context: Context) {
        val file = File(context.filesDir, "diagnostics.log")
        Diagnostics.install { msg -> append(file, msg) }
        append(file, "=== diagnostics started (pid ${android.os.Process.myPid()}, v${BuildConfig.VERSION_NAME}) ===")
        Log.d("MacSync", "DebugJournal -> ${file.absolutePath}")
    }

    @Synchronized
    private fun append(file: File, msg: String) {
        try {
            if (file.length() > MAX_BYTES) {
                file.writeText(file.readText().takeLast(MAX_BYTES / 2))
            }
            file.appendText("${fmt.format(Date())}  $msg\n")
        } catch (_: Exception) {
        }
    }
}
