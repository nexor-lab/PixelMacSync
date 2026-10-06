package it.luigi.macsync.unlock

import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import it.luigi.macsync.R

/**
 * Biometric gate for unbinding a Mac. Launched from the device list; on a
 * successful strong-biometric auth it removes the Keystore key, the local
 * trust, and tells the Mac to invalidate the phone.
 */
class UnbindActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val macId = intent.getStringExtra(EXTRA_MAC_ID)
        if (macId.isNullOrEmpty()) {
            finish()
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                PairingController.revoke(this@UnbindActivity, macId)
                finish()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                finish()
            }
        })

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.unbind_auth_title))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(getString(R.string.unlock_prompt_cancel))
            .build()
        prompt.authenticate(info)
    }

    companion object {
        const val EXTRA_MAC_ID = "macId"
    }
}
