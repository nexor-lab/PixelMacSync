package it.luigi.macsync.unlock

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import it.luigi.macsync.R

/**
 * Launched directly from the unlock notification's PendingIntent. Shows the
 * **native** BiometricPrompt bound (via CryptoObject) to the biometric-gated
 * Keystore key, signs the challenge transcript, and hands the signature back to
 * [UnlockController] for the BLE response.
 */
class UnlockActivity : FragmentActivity() {

    private val tag = "MacSyncUnlock"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val pending = UnlockController.currentPending()
        if (pending == null) {
            Log.d(tag, "event=unlock_activity_no_pending")
            finish()
            return
        }

        val phoneId = TrustedMacStore.deviceId(this)
        val transcriptResp = UnlockProtocol.transcriptResponse(pending.transcriptRequest, phoneId)

        val signature = when (val result = AndroidKeyStore.initUnlockSignature(pending.keyAlias)) {
            is AndroidKeyStore.UnlockSignatureResult.Ready -> result.signature
            is AndroidKeyStore.UnlockSignatureResult.PermanentFailure -> {
                Log.w(tag, "event=unlock_signature_init_failed permanent=true reason=${result.reason}")
                UnlockController.requireRepair(this, pending.macId)
                finish()
                return
            }
            is AndroidKeyStore.UnlockSignatureResult.TemporaryFailure -> {
                Log.w(tag, "event=unlock_signature_init_failed permanent=false reason=${result.reason}")
                showTemporaryFailure()
                finish()
                return
            }
        }
        Log.d(
            tag,
            "event=biometric_prompt_open authBound=" +
                "${AndroidKeyStore.requiresUserAuthentication(pending.keyAlias)} cryptoObject=true",
        )

        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Log.d(
                    tag,
                    "event=biometric_success cryptoObject=${result.cryptoObject != null}",
                )
                val s = result.cryptoObject?.signature
                if (s == null) {
                    Log.w(tag, "event=unlock_signature_missing_crypto_object")
                    showTemporaryFailure()
                    finish()
                    return
                }
                try {
                    val der = AndroidKeyStore.sign(s, transcriptResp)
                    Log.d(tag, "event=unlock_signature_success")
                    UnlockController.onSigned(
                        this@UnlockActivity,
                        UnlockProtocol.b64(pending.sessionId),
                        UnlockProtocol.b64(der),
                    )
                } catch (e: Exception) {
                    Log.e(
                        tag,
                        "event=unlock_signature_failed type=${e.javaClass.simpleName} " +
                            "error=${e.message}",
                    )
                    if (AndroidKeyStore.isPermanentKeyFailure(e)) {
                        UnlockController.requireRepair(this@UnlockActivity, pending.macId)
                    } else {
                        showTemporaryFailure()
                    }
                }
                UnlockNotification.dismiss(this@UnlockActivity)
                finish()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Log.w(tag, "biometric error $errorCode: $errString")
                UnlockController.cancel()
                finish()
            }
        }

        val prompt = BiometricPrompt(this, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.unlock_prompt_title))
            .setSubtitle(getString(R.string.unlock_prompt_subtitle, pending.macName))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(getString(R.string.unlock_prompt_cancel))
            .build()

        // Bind the signature to BiometricPrompt. This is required for legacy
        // auth-bound Keystore keys (otherwise sign() fails with
        // KEY_USER_NOT_AUTHENTICATED) and also remains valid for the current
        // non-auth-bound key policy.
        prompt.authenticate(info, BiometricPrompt.CryptoObject(signature))
    }

    private fun showTemporaryFailure() {
        UnlockController.cancel()
        UnlockNotification.dismiss(this)
        Toast.makeText(this, R.string.unlock_temporary_error, Toast.LENGTH_LONG).show()
    }
}
