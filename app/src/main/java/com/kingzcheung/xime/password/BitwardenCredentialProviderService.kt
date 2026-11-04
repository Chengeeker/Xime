package com.kingzcheung.xime.password

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class BitwardenCredentialProviderService : CredentialProviderService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        callback.onError(CreateCredentialUnknownException("Xime 当前支持获取 Bitwarden 密码，不支持创建通行密钥"))
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        val job = scope.launch {
            BitwardenVaultRepository.resumeSession(this@BitwardenCredentialProviderService)
            if (!cancellationSignal.isCanceled) getCredentialRequest(request, cancellationSignal, callback)
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    private fun getCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        if (cancellationSignal.isCanceled) return
        val passwordOptions = request.beginGetCredentialOptions.filterIsInstance<BeginGetPasswordOption>()
        if (passwordOptions.isEmpty()) {
            callback.onResult(BeginGetCredentialResponse.Builder().build())
            return
        }
        val status = BitwardenVaultRepository.status(this)
        if (!status.unlocked) {
            val response = BeginGetCredentialResponse.Builder()
            if (status.configured) {
                val unlockIntent = Intent(this, BitwardenUnlockActivity::class.java)
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    3401,
                    unlockIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                response.addAuthenticationAction(
                    AuthenticationAction.Builder("解锁 Xime 密码库", pendingIntent).build(),
                )
            }
            callback.onResult(response.build())
            return
        }
        callback.onResult(BitwardenCredentialEntries.build(this, passwordOptions, BitwardenVaultRepository.unlockedItems()))
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        callback.onResult(null)
    }
}

internal object BitwardenCredentialEntries {
    const val EXTRA_ITEM_ID = "xime.bitwarden.credential_id"

    fun build(
        context: android.content.Context,
        options: List<BeginGetPasswordOption>,
        items: List<BitwardenLoginItem>,
    ): BeginGetCredentialResponse {
        val response = BeginGetCredentialResponse.Builder()
        var requestCode = UUID.randomUUID().hashCode()
        for (option in options) {
            val allowedUserIds = option.allowedUserIds
            for (item in items) {
                if (item.username.isBlank() || item.password.isEmpty() ||
                    (allowedUserIds.isNotEmpty() && item.username !in allowedUserIds)
                ) continue
                val entryId = UUID.randomUUID().toString()
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    requestCode++,
                    Intent(context, BitwardenCredentialSelectionActivity::class.java)
                        .putExtra(EXTRA_ITEM_ID, item.id)
                        .setData(Uri.parse("xime-password://entry/$entryId")),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                val entry = androidx.credentials.provider.PasswordCredentialEntry.Builder(
                    context,
                    item.username,
                    pendingIntent,
                    option,
                ).setDisplayName(item.title)
                    .setAutoSelectAllowed(false)
                    .apply {
                        item.uris.firstNotNullOfOrNull(::domainOf)?.let(::setAffiliatedDomain)
                    }
                    .build()
                response.addCredentialEntry(entry)
            }
        }
        return response.build()
    }

    private fun domainOf(value: String): String? = runCatching {
        val uri = URI(if (value.contains("://")) value else "https://$value")
        uri.host?.lowercase()?.removePrefix("www.")
    }.getOrNull()?.takeIf(String::isNotBlank)
}

class BitwardenCredentialSelectionActivity : androidx.activity.ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        lifecycleScope.launch {
            BitwardenVaultRepository.resumeSession(this@BitwardenCredentialSelectionActivity)
            returnCredential()
        }
    }

    private fun returnCredential() {
        val item = intent.getStringExtra(BitwardenCredentialEntries.EXTRA_ITEM_ID)
            ?.let { BitwardenVaultRepository.item(this, it) }
        val request = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        if (item == null || request == null || item.username.isBlank() || item.password.isEmpty()) {
            setResult(RESULT_CANCELED)
        } else {
            runCatching {
                val responseIntent = Intent(intent)
                PendingIntentHandler.setGetCredentialResponse(
                    responseIntent,
                    GetCredentialResponse(PasswordCredential(item.username, item.password)),
                )
                setResult(RESULT_OK, responseIntent)
            }.onFailure { setResult(RESULT_CANCELED) }
        }
        finish()
    }
}
