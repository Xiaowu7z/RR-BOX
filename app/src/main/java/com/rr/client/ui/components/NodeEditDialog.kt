package com.rr.client.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.core.model.friendlyLabel

@Composable
fun NodeEditDialog(
    node: ProxyNode,
    onDismiss: () -> Unit,
    onSave: (ProxyNode) -> Unit
) {
    var tag by remember(node.id) { mutableStateOf(node.tag) }
    var server by remember(node.id) { mutableStateOf(node.server) }
    var port by remember(node.id) { mutableStateOf(node.serverPort.toString()) }
    var credential by remember(node.id) { mutableStateOf(node.uuidOrPassword) }
    var extraPassword by remember(node.id) { mutableStateOf(node.extraPassword) }
    var flow by remember(node.id) { mutableStateOf(node.flow) }
    var sni by remember(node.id) { mutableStateOf(node.sni) }
    var alpn by remember(node.id) { mutableStateOf(node.alpn) }
    var network by remember(node.id) { mutableStateOf(node.network) }
    var path by remember(node.id) { mutableStateOf(node.path) }
    var host by remember(node.id) { mutableStateOf(node.host) }
    var realityPublicKey by remember(node.id) { mutableStateOf(node.realityPublicKey) }
    var realityShortId by remember(node.id) { mutableStateOf(node.realityShortId) }
    var hoppingPorts by remember(node.id) { mutableStateOf(node.hoppingPorts) }
    var obfs by remember(node.id) { mutableStateOf(node.obfs) }
    var obfsPassword by remember(node.id) { mutableStateOf(node.obfsPassword) }
    var tlsEnabled by remember(node.id) { mutableStateOf(node.tlsEnabled) }
    var validationError by remember(node.id) { mutableStateOf<String?>(null) }

    fun field(label: String, value: String, onValueChange: (String) -> Unit) {
        // Placeholder for readability at call sites; Compose fields are emitted below.
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("编辑节点", fontWeight = FontWeight.Bold)
                Text(node.type.friendlyLabel())
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                EditField("节点名称", tag) { tag = it }
                EditField("服务器", server) { server = it }
                EditField("端口", port) { port = it.filter(Char::isDigit) }

                when (node.type) {
                    ProtocolType.VLESS_REALITY -> {
                        EditField("UUID", credential) { credential = it }
                        EditField("Flow", flow) { flow = it }
                        EditField("SNI", sni) { sni = it }
                        EditField("Reality 公钥", realityPublicKey) { realityPublicKey = it }
                        EditField("Reality Short ID", realityShortId) { realityShortId = it }
                        EditField("ALPN（逗号分隔）", alpn) { alpn = it }
                    }

                    ProtocolType.VLESS_TLS -> {
                        EditField("UUID", credential) { credential = it }
                        EditField("Flow", flow) { flow = it }
                        EditField("SNI", sni) { sni = it }
                        EditField("传输层（tcp/ws/grpc）", network) { network = it }
                        EditField("Path / gRPC Service", path) { path = it }
                        EditField("Host", host) { host = it }
                        EditField("ALPN（逗号分隔）", alpn) { alpn = it }
                        TlsSwitch(tlsEnabled) { tlsEnabled = it }
                    }

                    ProtocolType.VMESS_WS_ARGO,
                    ProtocolType.VMESS_TLS -> {
                        EditField("UUID", credential) { credential = it }
                        EditField("SNI", sni) { sni = it }
                        EditField("传输层（tcp/ws/grpc）", network) { network = it }
                        EditField("Path / gRPC Service", path) { path = it }
                        EditField("Host", host) { host = it }
                        EditField("ALPN（逗号分隔）", alpn) { alpn = it }
                        TlsSwitch(tlsEnabled) { tlsEnabled = it }
                    }

                    ProtocolType.HYSTERIA2 -> {
                        EditField("密码", credential) { credential = it }
                        EditField("SNI", sni) { sni = it }
                        EditField("ALPN（逗号分隔）", alpn) { alpn = it }
                        EditField("端口跳跃（逗号分隔）", hoppingPorts) { hoppingPorts = it }
                        EditField("Obfs", obfs) { obfs = it }
                        EditField("Obfs 密码", obfsPassword) { obfsPassword = it }
                    }

                    ProtocolType.TUIC_V5 -> {
                        EditField("UUID", credential) { credential = it }
                        EditField("密码", extraPassword) { extraPassword = it }
                        EditField("SNI", sni) { sni = it }
                        EditField("ALPN（逗号分隔）", alpn) { alpn = it }
                    }

                    else -> {
                        EditField("UUID / 密码", credential) { credential = it }
                        EditField("SNI", sni) { sni = it }
                        EditField("传输层", network) { network = it }
                        EditField("Path", path) { path = it }
                        EditField("Host", host) { host = it }
                        EditField("ALPN（逗号分隔）", alpn) { alpn = it }
                    }
                }

                validationError?.let {
                    Text(it)
                }
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsedPort = port.toIntOrNull()
                validationError = when {
                    tag.isBlank() -> "节点名称不能为空"
                    server.isBlank() -> "服务器地址不能为空"
                    parsedPort == null || parsedPort !in 1..65535 -> "端口必须是 1-65535"
                    credential.isBlank() && node.type !in setOf(
                        ProtocolType.NAIVE_H2,
                        ProtocolType.NAIVE_H3,
                        ProtocolType.CUSTOM
                    ) -> "认证信息不能为空"
                    else -> null
                }
                if (validationError == null) {
                    onSave(
                        node.copy(
                            tag = tag.trim(),
                            server = server.trim(),
                            serverPort = parsedPort!!,
                            uuidOrPassword = credential.trim(),
                            extraPassword = extraPassword,
                            flow = flow.trim(),
                            sni = sni.trim(),
                            alpn = alpn.trim(),
                            network = network.trim().lowercase(),
                            path = path,
                            host = host.trim(),
                            realityPublicKey = realityPublicKey.trim(),
                            realityShortId = realityShortId.trim(),
                            hoppingPorts = hoppingPorts.trim(),
                            obfs = obfs.trim(),
                            obfsPassword = obfsPassword,
                            tlsEnabled = tlsEnabled
                        )
                    )
                }
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun EditField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun TlsSwitch(enabled: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("TLS")
        Switch(checked = enabled, onCheckedChange = onChanged)
    }
}
