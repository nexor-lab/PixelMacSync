package it.luigi.macsync.unlock

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import it.luigi.macsync.R

/**
 * Shown when a Mac asks to pair. Displays the 6-digit SAS; the user must confirm
 * it matches the code shown on the Mac (MITM protection).
 */
class PairingActivity : FragmentActivity() {

    private var macName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pending = PairingController.current()
        if (pending == null) {
            finish()
            return
        }
        macName = pending.macName

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PairingScreen(
                        macName = pending.macName,
                        onConfirm = { authenticateThenConfirm() },
                        onDeny = {
                            PairingController.cancel(this@PairingActivity)
                            finish()
                        },
                    )
                }
            }
        }
    }

    /** Binding must be authorized by the owner: require a strong biometric first. */
    private fun authenticateThenConfirm() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                PairingController.confirm(this@PairingActivity)
                finish()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Leave the screen up so the user can retry or cancel.
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.pair_auth_title))
            .setSubtitle(getString(R.string.unlock_prompt_subtitle, macName))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(getString(R.string.unlock_prompt_cancel))
            .build()
        prompt.authenticate(info)
    }
}

@Composable
private fun PairingScreen(
    macName: String,
    onConfirm: () -> Unit,
    onDeny: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Mac 配对请求",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = macName,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "点击“确认配对”并用指纹验证，即可与该 Mac 建立解锁密钥。",
            style = MaterialTheme.typography.bodySmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onDeny) { Text("取消") }
            Button(onClick = onConfirm) { Text("确认配对") }
        }
    }
}
