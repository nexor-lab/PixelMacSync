package it.luigi.macsync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Transfers an app's launcher icon to the Mac once per app per process, over the
 * existing BLE notification channel, base64-chunked so it stays within the
 * US-separated UTF-8 protocol.
 *
 *   ICON_BEGIN US <package>
 *   ICON_DATA  US <package> US <seq> US <base64 chunk>
 *   ICON_END   US <package>
 *
 * The Mac reassembles and caches it at ~/Pictures/MacSyncIcons/<package>.png.
 * This does NOT require the Mac to have the app installed.
 */
object AppIconSender {

    private const val TAG = "MacSync"
    private const val TARGET_ICON_PX = 64
    private const val CHUNK_DELAY_MS = 18L
    private val sentPackages = mutableSetOf<String>()
    private val executor = Executors.newSingleThreadExecutor()

    /** Sends the icon for [packageName] once per process; [send] posts a payload. */
    @Synchronized
    fun sendIfNeeded(context: Context, packageName: String, send: (String) -> Unit) {
        if (packageName.isEmpty() || !sentPackages.add(packageName)) return
        // Serialise transfers on a background thread and pace the chunks so the
        // BLE stack is not flooded (notifications are unacknowledged).
        executor.execute { doSend(context, packageName, send) }
    }

    private fun doSend(context: Context, packageName: String, send: (String) -> Unit) {
        try {
            val pm = context.packageManager
            val drawable = pm.getApplicationIcon(packageName)
            val bitmap = toBitmap(drawable, TARGET_ICON_PX)
            val png = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
            val b64 = Base64.encodeToString(png, Base64.NO_WRAP)

            send("ICON_BEGIN\u001F$packageName")

            // Keep each packet within the 180-byte BLE budget.
            val overhead = "ICON_DATA\u001F$packageName\u001F999\u001F".length
            val chunk = maxOf(40, 176 - overhead)
            var seq = 0
            var i = 0
            while (i < b64.length) {
                val part = b64.substring(i, minOf(i + chunk, b64.length))
                send("ICON_DATA\u001F$packageName\u001F$seq\u001F$part")
                seq++
                i += chunk
                try { Thread.sleep(CHUNK_DELAY_MS) } catch (_: InterruptedException) {}
            }
            send("ICON_END\u001F$packageName")
            Log.d(TAG, "Icon sent for $packageName (${png.size} bytes, $seq chunks)")
        } catch (e: Exception) {
            sentPackages.remove(packageName)
            Log.w(TAG, "Icon extraction failed for $packageName: ${e.javaClass.simpleName}")
        }
    }

    private fun toBitmap(drawable: Drawable, size: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val src = drawable.bitmap
            return Bitmap.createScaledBitmap(src, size, size, true)
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp
    }
}
