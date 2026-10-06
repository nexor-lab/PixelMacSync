package it.luigi.macsync.unlock

import android.os.Bundle
import android.util.Log
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
            finish()
            return
        }

        val phoneId = TrustedMacStore.deviceId(this)
        val transcriptResp = UnlockProtocol.transcriptResponse(pending.transcriptRequest, phoneId)

        val signature = AndroidKeyStore.initSignatureForAuth(pending.keyAlias)
        if (signature == null) {
            Log.w(tag, "signing key missing or invalidated; re-pair required")
            UnlockController.cancel()
            UnlockNotification.dismiss(this)
            finish()
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val s = result.cryptoObject?.signature ?: signature
                try {
                    val der = AndroidKeyStore.sign(s, transcriptResp)
                    UnlockController.onSigned(
                        this@UnlockActivity,
                        UnlockProtocol.b64(pending.sessionId),
                        UnlockProtocol.b64(der),
                    )
                } catch (e: Exception) {
                    Log.e(tag, "sign failed: ${e.message}")
                    UnlockController.cancel()
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

        prompt.authenticate(info, BiometricPrompt.CryptoObject(signature))
    }
}
