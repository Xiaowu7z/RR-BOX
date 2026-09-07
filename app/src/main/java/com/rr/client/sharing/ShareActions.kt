package com.rr.client.sharing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

object ShareActions {
    const val MAX_CLIPBOARD_BYTES = 256 * 1024
    private const val MAX_TEXT_SHARE_BYTES = 64 * 1024

    fun canCopy(payload: SharePayload): Boolean =
        payload.text.toByteArray(Charsets.UTF_8).size <= MAX_CLIPBOARD_BYTES

    fun copy(context: Context, payload: SharePayload) {
        require(canCopy(payload))
        val clip = ClipData.newPlainText("RRBOX 配置", payload.text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }

    /** Call on IO; only the caller's explicit share button launches the intent. */
    fun prepareShare(context: Context, payload: SharePayload): Intent {
        val bytes = payload.text.toByteArray(Charsets.UTF_8)
        if (payload.kind == SharePayloadKind.LINK && bytes.size <= MAX_TEXT_SHARE_BYTES) {
            return Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, payload.text)
                putExtra(Intent.EXTRA_SUBJECT, payload.title)
            }
        }
        return prepareFileShare(context, payload.title, ShareExportDocument(payload.fileName, payload.mimeType, bytes))
    }

    fun prepareFileShare(context: Context, title: String, document: ShareExportDocument): Intent {
        require(document.bytes.size <= 8 * 1024 * 1024)
        val directory = File(context.cacheDir, "node-sharing").apply { mkdirs() }
        val files = directory.listFiles().orEmpty().sortedByDescending(File::lastModified)
        files.forEachIndexed { index, file ->
            if (index >= 7 || System.currentTimeMillis() - file.lastModified() > 24 * 60 * 60 * 1000L) file.delete()
        }
        val safeName = document.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
        val file = File(directory, "${UUID.randomUUID()}-$safeName")
        file.writeBytes(document.bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.sharing", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = document.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            clipData = ClipData.newUri(context.contentResolver, "RRBOX 配置", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
