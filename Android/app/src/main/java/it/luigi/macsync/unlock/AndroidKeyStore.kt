package it.luigi.macsync.unlock

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Android Keystore backed ECDSA P-256 signing key.
 *
 * The private key is generated inside the AndroidKeyStore, can never be
 * exported, and is usable **only** after a strong biometric authentication
 * (per-use; `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`).
 * Enrolling a new biometric invalidates the key, forcing a re-pair.
 */
object AndroidKeyStore {

    private const val PROVIDER = "AndroidKeyStore"

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun keyExists(alias: String): Boolean = keyStore().containsAlias(alias)

    /** True when the key exists AND can still be used for signing. Enrolling a
     *  new fingerprint invalidates biometric-gated keys; that must trigger a
     *  re-pair instead of leaving a notification that cannot open a prompt. */
    fun isUnlockKeyUsable(alias: String): Boolean =
        keyExists(alias) && initSignatureForAuth(alias) != null

    fun createKey(alias: String) {
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        // The key is hardware-backed and non-exportable, but deliberately NOT
        // user-authentication-bound: Android refuses to create auth-bound keys
        // until the phone has been unlocked with the PIN/pattern after boot,
        // which made pairing impossible after a normal fingerprint unlock.
        // Face/fingerprint approval is enforced by BiometricPrompt in the app
        // before every signature instead.
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        gen.initialize(spec)
        gen.generateKeyPair()
    }

    fun deleteKey(alias: String) {
        try { keyStore().deleteEntry(alias) } catch (_: Exception) {}
    }

    /** X.509 SubjectPublicKeyInfo DER of the public key, for pairing transfer. */
    fun publicKeyDer(alias: String): ByteArray? =
        try { keyStore().getCertificate(alias)?.publicKey?.encoded } catch (_: Exception) { null }

    /**
     * A [Signature] initialized for signing, to be handed to BiometricPrompt as
     * its CryptoObject. Returns null when the key is missing or was invalidated
     * by a biometric enrollment change.
     */
    fun initSignatureForAuth(alias: String): Signature? {
        val key = try {
            keyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            Signature.getInstance("SHA256withECDSA").apply {
                initSign(key.privateKey)
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /** DER-encoded ECDSA signature over [data]. Call only after auth succeeded. */
    fun sign(signature: Signature, data: ByteArray): ByteArray {
        signature.update(data)
        return signature.sign()
    }


    // --- session key: non-biometric, used only to authenticate the BLE session ---
    fun sessionKeyExists(alias: String): Boolean = keyExists(alias)

    fun createSessionKey(alias: String) {
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        gen.initialize(spec)
        gen.generateKeyPair()
    }

    fun signSession(alias: String, data: ByteArray): ByteArray? {
        val key = try {
            keyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        } catch (_: Exception) { null } ?: return null
        return try {
            Signature.getInstance("SHA256withECDSA").apply {
                initSign(key.privateKey)
                update(data)
            }.sign()
        } catch (_: Exception) { null }
    }

    fun canAuthenticateStrong(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
}
