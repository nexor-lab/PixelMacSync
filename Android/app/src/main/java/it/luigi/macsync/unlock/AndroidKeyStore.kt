package it.luigi.macsync.unlock

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.UnrecoverableKeyException
import java.security.spec.ECGenParameterSpec

/**
 * Android Keystore backed ECDSA P-256 signing key.
 *
 * The private key is generated inside the AndroidKeyStore and can never be
 * exported. Two unlock-key generations are intentionally supported:
 * - legacy keys enforce biometric authentication at the Keystore level;
 * - current keys are not auth-bound and rely on [UnlockActivity]'s prompt.
 * Both are passed to BiometricPrompt as a CryptoObject, so one signing path
 * works without forcing existing users to re-pair.
 */
object AndroidKeyStore {

    private const val PROVIDER = "AndroidKeyStore"

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun keyExists(alias: String): Boolean = keyStore().containsAlias(alias)

    /**
     * Cheap presence check used before posting an unlock notification.
     *
     * Do not initialize a Signature here: Keystore/signature providers can
     * be temporarily unavailable while the phone is locked. Treating that as a
     * permanently invalidated key revoked trust and showed a misleading
     * "re-pair required" toast as soon as UNLOCK_REQUEST arrived.
     */
    fun isUnlockKeyPresent(alias: String): Boolean =
        alias.isNotBlank() &&
            try {
                keyExists(alias)
            } catch (_: Exception) {
                false
            }

    /**
     * Whether the unlock key still uses the old Keystore-level biometric gate.
     * The shared-preference policy flag is not authoritative: an older pairing
     * could set it while keeping an auth-bound key, which then signs without an
     * auth token and fails with KEY_USER_NOT_AUTHENTICATED.
     */
    fun requiresUserAuthentication(alias: String): Boolean? {
        val privateKey = try {
            (keyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry)
                ?.privateKey
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            val factory = KeyFactory.getInstance(privateKey.algorithm, PROVIDER)
            (factory.getKeySpec(privateKey, KeyInfo::class.java) as? KeyInfo)
                ?.isUserAuthenticationRequired
        } catch (_: Exception) {
            null
        }
    }

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

    sealed interface UnlockSignatureResult {
        data class Ready(val signature: Signature) : UnlockSignatureResult
        data class PermanentFailure(val reason: String) : UnlockSignatureResult
        data class TemporaryFailure(val reason: String) : UnlockSignatureResult
    }

    /**
     * Initializes a signature for BiometricPrompt. Missing/unrecoverable keys
     * are permanent failures; provider or locked-device failures are temporary
     * and must not silently revoke the trusted Mac.
     */
    fun initUnlockSignature(alias: String): UnlockSignatureResult {
        val entry = try {
            keyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        } catch (e: Exception) {
            return if (isPermanentKeyFailure(e)) {
                UnlockSignatureResult.PermanentFailure(e.message ?: "key unavailable")
            } else {
                UnlockSignatureResult.TemporaryFailure(e.message ?: "keystore unavailable")
            }
        } ?: return UnlockSignatureResult.PermanentFailure("missing key")

        return try {
            UnlockSignatureResult.Ready(
                Signature.getInstance("SHA256withECDSA").apply {
                    initSign(entry.privateKey)
                }
            )
        } catch (e: Exception) {
            if (isPermanentKeyFailure(e)) {
                UnlockSignatureResult.PermanentFailure(e.message ?: "key invalidated")
            } else {
                UnlockSignatureResult.TemporaryFailure(e.message ?: "signature unavailable")
            }
        }
    }

    fun isPermanentKeyFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is KeyPermanentlyInvalidatedException ||
                current is UnrecoverableKeyException
            ) {
                return true
            }
            current = current.cause
        }
        return false
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
