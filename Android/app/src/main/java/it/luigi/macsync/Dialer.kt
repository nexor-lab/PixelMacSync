package it.luigi.macsync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Remote dialing (Mac -> phone). **Never places a real call.**
 *
 * The number is validated strictly, then the phone opens the **system dialer**
 * prefilled with it (`ACTION_DIAL`) — a *simulated dial*: the user still has to
 * press the call button themselves. `ACTION_CALL` is deliberately never used.
 *
 * With root/Shizuku the dialer is brought up via a privileged `am start` so it
 * works from the background; otherwise a plain `ACTION_DIAL` intent is used.
 *
 * The dialed number is never logged.
 */
object Dialer {

    private const val TAG = "MacSync"
    private val ALLOWED = Regex("^[+*#0-9]{1,20}$")

    /** Returns a sanitized number or null if invalid. */
    fun sanitize(number: String): String? {
        val n = number.trim().replace(Regex("[\\s\\-()]"), "")
        return if (ALLOWED.matches(n)) n else null
    }

    /** @return "ok" | "invalid" | "failed" */
    fun dial(context: Context, number: String): String {
        val n = sanitize(number) ?: return "invalid"

        // "Direct call" = place the call via the privileged shell (root/Shizuku),
        // no dialer confirmation. Otherwise open the system dialer (simulated dial).
        // Without privilege we always fall back to the dialer (never ACTION_CALL).
        val direct = ContactsRepository.directDial(context)
        if (PrivilegeManager.isCurrentMethodReady(context)) {
            val action = if (direct) "android.intent.action.CALL" else "android.intent.action.DIAL"
            val out = PrivilegeManager.exec(context, "am start -a $action -d tel:$n")
            val failed = out.contains("Error", true) || out.contains("Exception", true)
            if (!failed) {
                Log.d(TAG, if (direct) "Chiamata diretta (privilegiata)"
                       else "Dialer di sistema aperto (privilegiato)") // number never logged
                return "ok"
            }
            Log.w(TAG, "am start fallito: ${out.take(120)}")
        }

        // Fallback: open the system dialer directly.
        return try {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Log.d(TAG, "Dialer di sistema aperto")
            "ok"
        } catch (e: Exception) {
            Log.w(TAG, "Invio al dialer fallito: ${e.javaClass.simpleName}")
            "failed"
        }
    }
}
