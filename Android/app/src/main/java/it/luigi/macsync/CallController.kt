package it.luigi.macsync

import android.content.Context
import android.util.Log

/**
 * Call control (answer / end / mute) executed from a BLE command sent by the Mac.
 *
 * Android 15 has no shell `telecom` subcommand for accept/end/mute, and
 * `TelecomManager.acceptRingingCall()/endCall()` are restricted for non-default
 * dialers. The reliable path on this device is a privileged `input keyevent`
 * (root or Shizuku — see [PrivilegeManager]):
 *   - KEYCODE_CALL (5)    -> answer the ringing call
 *   - KEYCODE_ENDCALL (6) -> end / reject the call
 *   - KEYCODE_MUTE (91)   -> toggle microphone mute (best-effort)
 *
 * Never logs personal data (no numbers).
 */
object CallController {

    private const val TAG = "MacSync"
    private const val KEY_CALL = 5
    private const val KEY_ENDCALL = 6
    private const val KEY_MUTE = 91

    fun answer(context: Context): Boolean = key(context, KEY_CALL)
    fun end(context: Context): Boolean = key(context, KEY_ENDCALL)
    fun toggleMute(context: Context): Boolean = key(context, KEY_MUTE)

    private fun key(context: Context, keyCode: Int): Boolean {
        val out = PrivilegeManager.exec(context, "input keyevent $keyCode")
        // `input keyevent` prints nothing on success; only a thrown error is a failure.
        val failed = out.contains("Exception", true) || out.contains("Error:", true)
        if (failed) Log.w(TAG, "call keyevent $keyCode failed: ${out.take(120)}")
        return !failed
    }
}
