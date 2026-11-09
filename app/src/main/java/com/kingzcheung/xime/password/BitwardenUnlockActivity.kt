package com.kingzcheung.xime.password

import com.kingzcheung.xime.ui.theme.hapticTapFeedback

import com.kingzcheung.xime.ui.theme.hapticAction

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.PendingIntentHandler
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.theme.XimeTheme
import kotlinx.coroutines.launch

class BitwardenUnlockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val darkMode = SettingsPreferences.getDarkMode(this)
        setContent {
            XimeTheme(
                darkTheme = when (darkMode) {
                    2 -> isSystemInDarkTheme()
                    1 -> true
                    else -> false
                },
                themeId = SettingsPreferences.getKeyboardTheme(this),
            ) {
                BitwardenUnlockScreen(
                    onUnlocked = { unlockAndReturn() },
                    onCancel = { setResult(Activity.RESULT_CANCELED); finish() },
                )
            }
        }
    }

    private fun unlockAndReturn() {
        val beginRequest = PendingIntentHandler.retrieveBeginGetCredentialRequest(intent)
        if (beginRequest == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        val response = BitwardenCredentialEntries.build(
            this,
            beginRequest.beginGetCredentialOptions.filterIsInstance<BeginGetPasswordOption>(),
            BitwardenVaultRepository.unlockedItems(),
        )
        if (response.credentialEntries.isEmpty()) {
            setResult(Activity.RESULT_CANCELED)
        } else {
            val resultIntent = Intent(intent)
            PendingIntentHandler.setBeginGetCredentialResponse(resultIntent, response)
            setResult(Activity.RESULT_OK, resultIntent)
        }
        finish()
    }
}

@Composable
private fun BitwardenUnlockScreen(onUnlocked: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val account = remember { BitwardenVaultRepository.status(context).email }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (BitwardenVaultRepository.resumeSession(context)) onUnlocked()
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { insets ->
        Box(
            modifier = Modifier.fillMaxSize().padding(insets).padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("解锁 Xime 密码库", style = MaterialTheme.typography.headlineSmall)
                Text(account, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = "" },
                    modifier = (Modifier.fillMaxWidth()).hapticTapFeedback(),
                    label = { Text("Bitwarden 主密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    enabled = !busy,
                )
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = hapticAction(Button@ {
                        busy = true
                        error = ""
                        scope.launch {
                            when (val result = BitwardenVaultRepository.unlock(context, password)) {
                                is BitwardenOperation.Success -> onUnlocked()
                                is BitwardenOperation.Failure -> { error = result.message; busy = false }
                                BitwardenOperation.TwoFactorRequired -> { error = "请在 Xime 密码设置中完成账户验证"; busy = false }
                            }
                        }
                    }),
                    enabled = password.isNotEmpty() && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp), strokeWidth = 2.dp)
                    Text("解锁并继续")
                }
                androidx.compose.material3.TextButton(onClick = hapticAction(onCancel), enabled = !busy) { Text("取消") }
            }
        }
    }
}
