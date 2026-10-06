package it.luigi.macsync.unlock

import java.nio.ByteBuffer
import java.util.Base64

/**
 * Unlock wire protocol (see .opencode/UNLOCK_DESIGN.md).
 *
 * Reuses the existing BLE `US`-separated text transport; binary fields ride as
 * standard base64 (padding kept, so it matches Swift's base64EncodedString()).
 * The signed transcript is a length-prefixed, unambiguous byte string shared
 * byte-for-byte with the macOS side (SignatureVerifier.swift).
 */
object UnlockProtocol {

    const val VERSION = 1
    const val SEP = "\u001F"

    /** Longest a Mac request may be in flight before the phone ignores it. */
    const val DEFAULT_TTL_MS = 10_000L

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private fun lp(data: ByteArray): ByteArray {
        val len = data.size
        require(len in 0..0xFFFF) { "field too long: $len" }
        return ByteBuffer.allocate(2 + len).putShort(len.toShort()).put(data).array()
    }

    private fun lpU64(v: Long): ByteArray =
        ByteBuffer.allocate(8).putLong(v).array()

    private fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var p = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, p, part.size)
            p += part.size
        }
        return out
    }

    /**
     * transcript_req = LP(version) ‖ LP(session_id) ‖ LP(mac_id) ‖ LP(challenge)
     *                  ‖ LP_u64(expires_at)
     * Covers every security-relevant field (spec: do not sign only the challenge).
     */
    fun transcriptRequest(
        version: Int,
        sessionId: ByteArray,
        macId: String,
        challenge: ByteArray,
        expiresAt: Long,
    ): ByteArray = concat(
        lp(byteArrayOf(version.toByte())),
        lp(sessionId),
        lp(macId.toByteArray(Charsets.UTF_8)),
        lp(challenge),
        lpU64(expiresAt),
    )

    /**
     * transcript_resp = transcript_req ‖ LP(phone_id)
     * The phone signature therefore also binds the phone identity.
     */
    fun transcriptResponse(transcriptRequest: ByteArray, phoneId: String): ByteArray =
        concat(transcriptRequest, lp(phoneId.toByteArray(Charsets.UTF_8)))

    /** SAS for pairing: 6 decimal digits from SHA-256, identical on both sides. */
    fun pairingSas(pkMac: ByteArray, pkPhone: ByteArray, nonceMac: ByteArray, noncePhone: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        // sorted(pkMac, pkPhone) so the order is canonical on both devices
        val first: ByteArray
        val second: ByteArray
        if (compare(pkMac, pkPhone) <= 0) { first = pkMac; second = pkPhone } else { first = pkPhone; second = pkMac }
        md.update(first); md.update(second); md.update(nonceMac); md.update(noncePhone)
        val d = md.digest()
        val n = ((d[0].toInt() and 0xFF) shl 24 or ((d[1].toInt() and 0xFF) shl 16) or
                 ((d[2].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF)).toLong() and 0xFFFFFFFFL
        return "%06d".format(n % 1_000_000)
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a[i].toInt() and 0xFF
            val y = b[i].toInt() and 0xFF
            if (x != y) return x - y
        }
        return a.size - b.size
    }
}
