package com.rr.client.sharing

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ShareExportDocument(val fileName: String, val mimeType: String, val bytes: ByteArray)

class ShareDocumentState : ViewModel() {
    internal var pending: ShareExportDocument? = null
    internal var launcher: ActivityResultLauncher<ShareExportDocument>? = null
}

/** Registered before the PIN-gated Compose tree; keeps a snapshot across rotation. */
object ShareDocumentExporter {
    private const val RESULT_KEY = "rrbox-save-shared-document"
    private const val MAX_BYTES = 8 * 1024 * 1024

    fun install(activity: ComponentActivity) {
        val state = ViewModelProvider(activity)[ShareDocumentState::class.java]
        val launcher = activity.activityResultRegistry.register(RESULT_KEY, activity, CreateDocument()) { uri ->
            val document = state.pending
            state.pending = null
            if (uri == null) return@register
            if (document == null) {
                Toast.makeText(activity, "待保存内容已失效，请重新导出", Toast.LENGTH_LONG).show()
                return@register
            }
            val app = activity.applicationContext
            state.viewModelScope.launch {
                val success = withContext(Dispatchers.IO) {
                    runCatching {
                        val stream = app.contentResolver.openOutputStream(uri, "wt") ?: error("No output stream")
                        stream.use { it.write(document.bytes) }
                    }.isSuccess
                }
                Toast.makeText(app, if (success) "文件已保存" else "保存失败，请重新选择位置", Toast.LENGTH_SHORT).show()
            }
        }
        state.launcher = launcher
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY && state.launcher === launcher) state.launcher = null
        })
    }

    fun save(context: Context, document: ShareExportDocument): Boolean {
        if (document.bytes.size > MAX_BYTES) return false
        val activity = findActivity(context) ?: return false
        val state = ViewModelProvider(activity)[ShareDocumentState::class.java]
        val launcher = state.launcher ?: return false
        if (state.pending != null) return false
        state.pending = document
        return runCatching { launcher.launch(document) }.isSuccess.also { success ->
            if (!success) state.pending = null
        }
    }

    private fun findActivity(context: Context): ComponentActivity? = when (context) {
        is ComponentActivity -> context
        is ContextWrapper -> if (context.baseContext !== context) findActivity(context.baseContext) else null
        else -> null
    }

    private class CreateDocument : ActivityResultContract<ShareExportDocument, Uri?>() {
        override fun createIntent(context: Context, input: ShareExportDocument): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = input.mimeType
                putExtra(Intent.EXTRA_TITLE, input.fileName)
            }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
    }
}
