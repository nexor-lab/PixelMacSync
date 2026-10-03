package it.luigi.macsync

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Reuses the existing NotificationListenerService identity to observe and
 * control the phone's active media sessions over the BLE link.
 *
 *   Android -> Mac (Notifications channel, US-separated):
 *     MUSIC_META   US title US artist US album US durationMs US positionMs US state [US coverKey]
 *     ART_BEGIN    US key
 *     ART_DATA     US key US seq US <base64 chunk>
 *     ART_END      US key
 *     MUSIC_VOLUME US <percent 0..100>
 *
 *   Mac -> Android (Command channel):
 *     MUSIC_PLAY / MUSIC_PAUSE / MUSIC_NEXT / MUSIC_PREV / MUSIC_SEEK US ms /
 *     MUSIC_STATUS / MUSIC_VOLUME_SET US <percent 0..100>
 *
 * It is purely event driven (active-session + metadata + playback callbacks);
 * no polling. Cover art is transferred once per track (base64 JPEG, chunked and
 * paced) and cached on the Mac.
 */
object MediaSessionMonitor {

    private const val TAG = "MacSync"
    private const val ART_MAX_PX = 256
    private const val ART_CHUNK_DELAY_MS = 18L

    // MediaSessionManager.getActiveSessions() requires an enabled
    // NotificationListenerService component; we are called from ours.
    private var appContext: Context? = null
    private var sessionManager: MediaSessionManager? = null
    private var listenerComponent: ComponentName? = null

    private var activeController: MediaController? = null
    private var audioManager: AudioManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val coverExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var lastVolumePercent = -1

    // STREAM_MUSIC volume changes (settings key "volume_music") are observed so
    // the Mac slider tracks what the user does with the phone's volume keys.
    private val volumeObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            // Ignore unrelated Settings.System changes (brightness, etc.).
            if (uri == null || uri.toString().contains("volume")) {
                sendVolume(force = false)
            }
        }
    }

    @Volatile private var sentCoverKey: String? = null
    // Key of the transfer currently running (guards against a metadata refresh
    // for the same track queueing a duplicate send).
    @Volatile private var activeCoverKey: String? = null
    // The cover key we currently want on the wire. Setting a new value makes any
    // in-flight transfer abort on its next chunk, so skipping tracks quickly can
    // never let a stale cover win.
    @Volatile private var requestedCoverKey: String? = null

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            onActiveSessionsChanged(controllers)
        }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
    }

    @Synchronized
    fun start(context: Context, component: ComponentName) {
        if (sessionManager != null) {
            // Already running; keep the active controller fresh.
            refreshActiveSessions()
            return
        }
        appContext = context.applicationContext
        listenerComponent = component
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        if (manager == null) {
            Log.w(TAG, "MediaSessionManager non disponibile")
            return
        }
        sessionManager = manager
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
            onActiveSessionsChanged(manager.getActiveSessions(component))
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI, true, volumeObserver
            )
            sendVolume(force = true)
            Log.d(TAG, "MediaSessionMonitor avviato")
        } catch (e: Exception) {
            Log.e(TAG, "Impossibile registrare il listener media: ${e.message}")
            sessionManager = null
        }
    }

    @Synchronized
    fun stop() {
        val manager = sessionManager ?: return
        try {
            manager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (_: Exception) {}
        try {
            appContext?.contentResolver?.unregisterContentObserver(volumeObserver)
        } catch (_: Exception) {}
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
        audioManager = null
        sessionManager = null
        appContext = null
        sentCoverKey = null
        activeCoverKey = null
        requestedCoverKey = null
        lastVolumePercent = -1
        Log.d(TAG, "MediaSessionMonitor fermato")
    }

    private fun refreshActiveSessions() {
        val manager = sessionManager ?: return
        val component = listenerComponent ?: return
        try {
            onActiveSessionsChanged(manager.getActiveSessions(component))
        } catch (e: Exception) {
            Log.e(TAG, "getActiveSessions fallito: ${e.message}")
        }
    }

    private fun onActiveSessionsChanged(controllers: List<MediaController>?) {
        // Prefer the session that is actually playing, then any with metadata.
        val chosen = controllers?.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers?.firstOrNull {
            val m = it.metadata
            !m?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() ||
                !m?.getString(MediaMetadata.METADATA_KEY_ARTIST).isNullOrBlank()
        } ?: controllers?.firstOrNull()

        if (activeController?.sessionToken != chosen?.sessionToken) {
            activeController?.unregisterCallback(controllerCallback)
            activeController = chosen
            activeController?.registerCallback(controllerCallback, mainHandler)
        }
        publish()
    }

    // --- Stato corrente -> Mac ---

    private fun currentController(): MediaController? = activeController

    private fun publish() {
        val controller = currentController() ?: run {
            sendMeta("", "", "", 0L, 0L, "stopped", "")
            return
        }
        val meta = controller.metadata
        val playback = controller.playbackState

        val title = sanitize(meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty())
        val artist = sanitize(
            meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        )
        val album = sanitize(meta?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty())
        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = playback?.position ?: 0L
        val hasTrack = title.isNotBlank() || artist.isNotBlank() || album.isNotBlank()

        val state = when {
            playback?.state == PlaybackState.STATE_PLAYING -> "playing"
            playback?.state == PlaybackState.STATE_STOPPED ||
                playback?.state == PlaybackState.STATE_NONE ||
                playback?.state == PlaybackState.STATE_ERROR -> "stopped"
            !hasTrack -> "stopped"
            else -> "paused"
        }

        val key = if (hasTrack) coverKey(controller.packageName, title, artist, album) else ""

        // The key travels with the metadata so the Mac always knows which cover
        // belongs to the current track (and can show a cached one instantly).
        sendMeta(title, artist, album, duration, position, state, key)

        // Send once per key. A new track has a new key, which both triggers the
        // send and supersedes any transfer still running (`requestedCoverKey`).
        val art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        if (art != null && hasTrack && key != sentCoverKey && key != activeCoverKey) {
            activeCoverKey = key
            requestedCoverKey = key
            sendCover(key, art)
        }
    }

    private fun sendMeta(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        positionMs: Long,
        state: String,
        coverKey: String
    ) {
        val payload = "MUSIC_META\u001F$title\u001F$artist\u001F$album\u001F$durationMs\u001F$positionMs\u001F$state\u001F$coverKey"
        Log.d(TAG, "Invio stato musica: $state - $title / $artist (cover=$coverKey)")
        send(payload)
    }

    private fun sendCover(key: String, bitmap: Bitmap) {
        coverExecutor.execute {
            if (key != requestedCoverKey) return@execute
            try {
                val scaled = scaleDown(bitmap, ART_MAX_PX)
                val jpeg = ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
                    out.toByteArray()
                }
                val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)

                if (key != requestedCoverKey) return@execute
                send("ART_BEGIN\u001F$key")
                val overhead = "ART_DATA\u001F$key\u001F999\u001F".length
                val chunk = maxOf(40, 176 - overhead)
                var seq = 0
                var i = 0
                while (i < b64.length) {
                    // A newer track was requested: drop this stale transfer.
                    if (key != requestedCoverKey) {
                        Log.d(TAG, "Copertina superata, annullo $key dopo $seq pacchetti")
                        return@execute
                    }
                    val part = b64.substring(i, minOf(i + chunk, b64.length))
                    send("ART_DATA\u001F$key\u001F$seq\u001F$part")
                    seq++
                    i += chunk
                    try { Thread.sleep(ART_CHUNK_DELAY_MS) } catch (_: InterruptedException) {}
                }
                send("ART_END\u001F$key")
                sentCoverKey = key
                Log.d(TAG, "Copertina inviata per $key (${jpeg.size} bytes, $seq pacchetti)")
            } catch (e: Exception) {
                Log.w(TAG, "Invio copertina fallito: ${e.javaClass.simpleName}")
            } finally {
                if (activeCoverKey == key) activeCoverKey = null
            }
        }
    }

    // Stable per-track key. Album is excluded (players sometimes populate it
    // late / inconsistently, which would churn the key and re-send the cover);
    // it is only used when the artist is missing.
    private fun coverKey(pkg: String, title: String, artist: String, album: String): String {
        val identity = "$title|" + (artist.ifBlank { album })
        return "$pkg-${Integer.toHexString(identity.hashCode())}"
    }

    private fun scaleDown(source: Bitmap, maxPx: Int): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (largest <= maxPx) return source
        val ratio = maxPx.toFloat() / largest.toFloat()
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun sanitize(value: String?): String =
        value.orEmpty().replace('\u001F', ' ').trim()

    private fun send(payload: String) {
        val ctx = appContext ?: return
        val intent = Intent("it.luigi.macsync.NEW_NOTIFICATION")
        intent.putExtra("payload", payload)
        intent.setPackage(ctx.packageName)
        ctx.sendBroadcast(intent)
    }

    // --- Comandi Mac -> Android ---

    fun play() = withControls { it.play() }
    fun pause() = withControls { it.pause() }
    fun next() = withControls { it.skipToNext() }
    fun previous() = withControls { it.skipToPrevious() }
    fun seekTo(positionMs: Long) = withControls { it.seekTo(positionMs) }

    // --- Volume (STREAM_MUSIC) ---

    /** Sets the media volume to [percent] (0..100). */
    fun setVolume(percent: Int) {
        mainHandler.post {
            val am = audioManager ?: run {
                Log.w(TAG, "setVolume ignorato: AudioManager non pronto")
                return@post
            }
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val value = (percent.coerceIn(0, 100) * max + 50) / 100
            val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
            } catch (e: SecurityException) {
                Log.e(TAG, "setStreamVolume SecurityException: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "setStreamVolume error: ${e.javaClass.simpleName}: ${e.message}")
            }
            val after = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            Log.d(TAG, "setVolume $percent% -> $value (prima=$before, dopo=$after, max=$max)")
            if (after != value) {
                Log.w(TAG, "Il volume non è stato applicato (OEM/contenuto?): richiesto=$value reale=$after")
            }
            // Echo the real value even if the Settings ContentObserver did not
            // fire (MIUI/HyperOS sometimes does not), so the Mac slider stays in sync.
            sendVolume(force = true)
        }
    }

    fun requestVolume() = sendVolume(force = true)

    private fun sendVolume(force: Boolean) {
        val am = audioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val percent = (current * 100 + max / 2) / max
        if (!force && percent == lastVolumePercent) return
        lastVolumePercent = percent
        Log.d(TAG, "Invio volume: $percent% ($current/$max)")
        send("MUSIC_VOLUME\u001F$percent")
    }

    /** Re-sends current metadata, cover and volume — after a Mac (re)connect. */
    fun requestState() {
        // Force a cover re-send, but do not touch the in-flight bookkeeping (so
        // a running transfer is neither aborted nor duplicated).
        sentCoverKey = null
        mainHandler.post { publish() }
        sendVolume(force = true)
    }

    private fun withControls(action: (android.media.session.MediaController.TransportControls) -> Unit) {
        mainHandler.post {
            val controls = activeController?.transportControls
            if (controls == null) {
                Log.d(TAG, "Comando media ignorato: nessun controller attivo")
            } else {
                action(controls)
            }
        }
    }
}
