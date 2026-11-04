package com.kingzcheung.xime.password

import android.app.assist.AssistStructure
import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.os.Build
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillContext
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.text.InputType
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.kingzcheung.xime.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BitwardenAutofillService : AutofillService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val job = scope.launch {
            BitwardenVaultRepository.resumeSession(this@BitwardenAutofillService)
            if (!cancellationSignal.isCanceled) fillRequest(request, cancellationSignal, callback)
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    private fun fillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        try {
            val structure = request.fillContexts.lastOrNull()?.structure
            if (structure == null || cancellationSignal.isCanceled) {
                callback.onSuccess(null)
                return
            }
            val fields = findCredentialFields(structure)
            if (fields.password == null && fields.username == null) {
                callback.onSuccess(null)
                return
            }
            val packageName = structure.activityComponent?.packageName
            val host = fields.focused?.webDomain ?: fields.rootWebDomain.orEmpty()
            val matches = BitwardenVaultRepository.matchingItems(this, host, packageName)
            if (matches.isEmpty() || cancellationSignal.isCanceled) {
                callback.onSuccess(null)
                return
            }

            val response = FillResponse.Builder()
            val inlineSpec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                request.inlineSuggestionsRequest?.inlinePresentationSpecs?.firstOrNull()
            } else null
            val inlineAttribution = if (inlineSpec != null) {
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else null
            matches.forEach { item ->
                val presentation = RemoteViews(this.packageName, android.R.layout.simple_list_item_1).apply {
                    setTextViewText(android.R.id.text1, listOf(item.title, item.username).filter(String::isNotBlank).joinToString(" · "))
                }
                val inlinePresentation = if (inlineSpec != null && inlineAttribution != null) {
                    val content = InlineSuggestionUi.newContentBuilder(inlineAttribution)
                        .setTitle(item.username.ifBlank { item.title })
                        .setSubtitle("Bitwarden · ${item.title}")
                        .setContentDescription("填充 ${item.title} 的登录信息")
                        .build()
                    InlinePresentation(content.slice, inlineSpec, false)
                } else null
                val dataset = Dataset.Builder(presentation)
                var hasValue = false
                fields.username?.let {
                    if (inlinePresentation != null) dataset.setValue(it, AutofillValue.forText(item.username), presentation, inlinePresentation)
                    else dataset.setValue(it, AutofillValue.forText(item.username))
                    hasValue = true
                }
                fields.password?.let {
                    if (inlinePresentation != null) dataset.setValue(it, AutofillValue.forText(item.password), presentation, inlinePresentation)
                    else dataset.setValue(it, AutofillValue.forText(item.password))
                    hasValue = true
                }
                if (hasValue) response.addDataset(dataset.build())
            }
            callback.onSuccess(response.build())
        } catch (_: Exception) {
            callback.onFailure("Xime 无法读取此页面的自动填充字段")
        }
    }

    override fun onSaveRequest(request: android.service.autofill.SaveRequest, callback: android.service.autofill.SaveCallback) {
        callback.onSuccess()
    }

    private fun findCredentialFields(structure: AssistStructure): CredentialFields {
        val fields = CredentialFields()
        for (windowIndex in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(windowIndex).rootViewNode
            if (fields.rootWebDomain.isNullOrBlank()) fields.rootWebDomain = root.webDomain
            visit(root, fields)
        }
        return fields
    }

    private fun visit(node: AssistStructure.ViewNode, fields: CredentialFields) {
        val id = node.autofillId
        if (id != null) {
            val hints = node.autofillHints.orEmpty().toSet()
            val isPassword = hints.any { it == View.AUTOFILL_HINT_PASSWORD } || isPasswordInput(node.inputType)
            val isUsername = hints.any {
                it == View.AUTOFILL_HINT_USERNAME || it == View.AUTOFILL_HINT_EMAIL_ADDRESS
            }
            if (isPassword && fields.password == null) fields.password = id
            if (isUsername && fields.username == null) fields.username = id
            if (node.isFocused) fields.focused = node
        }
        for (index in 0 until node.childCount) visit(node.getChildAt(index), fields)
    }

    private fun isPasswordInput(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private class CredentialFields {
        var username: AutofillId? = null
        var password: AutofillId? = null
        var focused: AssistStructure.ViewNode? = null
        var rootWebDomain: String? = null
    }
}
