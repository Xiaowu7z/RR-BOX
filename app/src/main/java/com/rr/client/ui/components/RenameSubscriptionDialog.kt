package com.rr.client.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

@Composable
fun RenameSubscriptionDialog(name: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var input by remember(name) { mutableStateOf(name) }
    val normalized = input.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名订阅分组") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("分组名称") },
                singleLine = true,
                supportingText = { Text("${normalized.length}/80") },
                isError = normalized.length > 80,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onRename(normalized) }, enabled = normalized.length in 1..80) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
