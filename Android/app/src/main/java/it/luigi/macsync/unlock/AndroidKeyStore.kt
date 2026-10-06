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

    fun createKey(alias: String) {
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            // 0 => authentication required for every single use
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
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

    fun canAuthenticateStrong(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
}
