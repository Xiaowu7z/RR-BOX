package com.rr.client.ui.components

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rr.client.sharing.*
import com.rr.client.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
fun SharePayloadDialog(payload: SharePayload, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(payload) { mutableStateOf(false) }
    var qrReady by remember(payload) { mutableStateOf(false) }
    var bitmap by remember(payload) { mutableStateOf<Bitmap?>(null) }
    val copyAllowed = remember(payload) { ShareActions.canCopy(payload) }
    fun save(document: ShareExportDocument) {
        if (!ShareDocumentExporter.save(context, document))
            Toast.makeText(context, "无法保存，请完成当前导出后重试", Toast.LENGTH_SHORT).show()
    }
    fun shareQr() {
        val currentBitmap = bitmap ?: return
        scope.launch {
            busy = true
            val intent = withContext(Dispatchers.IO) {
                runCatching { ShareActions.prepareFileShare(context, payload.title, qrDocument(currentBitmap)) }.getOrNull()
            }
            val success = intent != null && runCatching {
                context.startActivity(Intent.createChooser(intent, "分享二维码"))
            }.isSuccess
            busy = false
            if (!success) Toast.makeText(context, "无法分享二维码，请保存图片", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(payload) {
        bitmap = withContext(Dispatchers.Default) {
            ShareQrEncoder.encode(payload.text)?.let { matrix ->
                val pixels = IntArray(matrix.width * matrix.height) { index ->
                    if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
                Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            }
        }
        qrReady = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(payload.title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!qrReady) {
                    CircularProgressIndicator(modifier = Modifier.size(40.dp))
                } else if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = "分享二维码",
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White)
                    )
                } else {
                    Text("内容较长，无法生成可扫描的二维码。请复制内容或保存文件。", color = TextSecondary)
                }
                Text(
                    payload.notice ?: if (payload.kind == SharePayloadKind.JSON) "原始节点配置：可作为 sing-box JSON 导入；若包含其他出站或证书文件引用，接收端也需要对应内容。"
                    else "扫码或复制链接，即可在支持该格式的客户端导入。",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val success = runCatching { ShareActions.copy(context, payload) }.isSuccess
                        Toast.makeText(context, if (success) "已复制" else "复制失败，请保存文件", Toast.LENGTH_SHORT).show()
                    }, enabled = !busy && copyAllowed, modifier = Modifier.weight(1f)) {
                        Text(if (payload.kind == SharePayloadKind.LINK) "复制链接" else "复制配置")
                    }
                    Button(onClick = {
                        scope.launch {
                            busy = true
                            val intent = withContext(Dispatchers.IO) {
                                runCatching { ShareActions.prepareShare(context, payload) }.getOrNull()
                            }
                            val success = intent != null && runCatching {
                                context.startActivity(Intent.createChooser(intent, "分享 ${payload.title}"))
                            }.isSuccess
                            busy = false
                            if (!success) Toast.makeText(context, "无法打开分享面板，请复制或保存文件", Toast.LENGTH_SHORT).show()
                        }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(if (payload.kind == SharePayloadKind.LINK) "分享链接" else "分享配置")
                    }
                }
                OutlinedButton(onClick = {
                    save(ShareExportDocument(payload.fileName, payload.mimeType, payload.text.toByteArray(Charsets.UTF_8)))
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("保存文件") }
                if (bitmap != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val currentBitmap = bitmap ?: return@OutlinedButton
                            scope.launch {
                                busy = true
                                val document = withContext(Dispatchers.Default) { qrDocument(currentBitmap) }
                                busy = false
                                save(document)
                            }
                        }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("保存二维码") }
                        OutlinedButton(onClick = ::shareQr, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text("分享二维码")
                        }
                    }
                }
                if (!copyAllowed) Text("内容超过剪贴板容量，请使用文件分享或保存。", color = TextSecondary)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

private fun qrDocument(bitmap: Bitmap): ShareExportDocument = ByteArrayOutputStream().use { output ->
    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
    ShareExportDocument("RRBOX-QR.png", "image/png", output.toByteArray())
}
