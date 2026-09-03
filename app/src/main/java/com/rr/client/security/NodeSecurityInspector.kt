package com.rr.client.security

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode

enum class NodeSecuritySeverity { PASS, WARNING, DANGER }
enum class NodeSecurityRating { GOOD, ATTENTION, HIGH_RISK }

data class NodeSecurityFinding(val title: String, val detail: String, val severity: NodeSecuritySeverity)
data class NodeSecurityReport(val rating: NodeSecurityRating, val title: String, val summary: String, val findings: List<NodeSecurityFinding>)

object NodeSecurityInspector {
    fun inspect(node: ProxyNode): NodeSecurityReport {
        val findings = mutableListOf<NodeSecurityFinding>()
        val raw = parseRaw(node.rawJson)
        val tls = raw?.objectOrNull("tls")
        val reality = tls?.objectOrNull("reality")
        val tlsEnabled = if (tls != null) !tls.has("enabled") || tls.boolean("enabled") else node.tlsEnabled
        val insecure = tls?.boolean("insecure") == true
        val realityEnabled = reality?.boolean("enabled") == true || node.type == ProtocolType.VLESS_REALITY
        val realityPublicKey = reality?.string("public_key").orEmpty().ifBlank { node.realityPublicKey }
        val sni = tls?.string("server_name").orEmpty().ifBlank { node.sni }

        when (node.type) {
            ProtocolType.VLESS_REALITY -> if (realityPublicKey.isBlank()) findings.danger("Reality 参数缺失", "未检测到 Reality 公钥，节点配置可能不完整。") else findings.pass("Reality 已启用", "检测到 Reality 公钥，外层握手具备服务端身份校验材料。")
            ProtocolType.VLESS_TLS -> if (tlsEnabled) findings.pass("TLS 已启用", "VLESS 外层使用 TLS。") else findings.danger("VLESS 未启用 TLS", "VLESS 本身不提供内容加密，裸 TCP 使用存在明显暴露风险。")
            ProtocolType.VMESS_TLS, ProtocolType.VMESS_WS_ARGO -> if (tlsEnabled) findings.pass("TLS 已启用", "VMess 外层使用 TLS。") else findings.warning("VMess 未启用 TLS", "协议仍有自身保护，但缺少 TLS 外层，不建议在不可信网络长期使用。")
            ProtocolType.HYSTERIA1, ProtocolType.HYSTERIA2, ProtocolType.TUIC_V5, ProtocolType.ANYTLS, ProtocolType.NAIVE_H2, ProtocolType.NAIVE_H3, ProtocolType.TROJAN -> if (tlsEnabled) findings.pass("加密传输已启用", "该协议当前配置包含 TLS 安全层。") else findings.danger("TLS 安全层缺失", "该协议正常应依赖 TLS，当前配置需要重新检查。")
            ProtocolType.SHADOWSOCKS -> inspectShadowsocks(node, raw, findings)
            ProtocolType.SOCKS -> findings.warning("SOCKS 本身无加密", "远程 SOCKS 连接不提供传输层加密，仅适合可信内网或外层已有安全隧道的场景。")
            ProtocolType.HTTP -> if (tlsEnabled) findings.pass("HTTPS 代理", "HTTP 代理外层启用了 TLS。") else findings.danger("明文 HTTP 代理", "代理认证和流量可能在传输途中暴露。")
            ProtocolType.SSH -> findings.pass("SSH 加密", "SSH 协议本身提供加密和服务端身份校验机制。")
            ProtocolType.WIREGUARD -> findings.pass("WireGuard 加密", "WireGuard 数据面使用现代加密协议。")
            ProtocolType.SHADOWTLS -> findings.pass("ShadowTLS 外层", "检测到 ShadowTLS 类型；安全性仍依赖完整的服务端组合配置。")
            ProtocolType.SNELL -> findings.pass("Snell 加密", "协议本身提供加密；仍建议保持客户端与服务端版本一致。")
            ProtocolType.TOR -> findings.pass("Tor 加密链路", "Tor 流量使用分层加密，但出口节点仍可看到明文应用层流量。")
            ProtocolType.CUSTOM -> findings.warning("自定义协议", "无法仅凭 RRBOX 的结构化字段判断完整安全属性，请检查原始 sing-box 配置。")
        }

        inspectAuthentication(node, findings)
        if (tlsEnabled && !realityEnabled) {
            if (insecure) findings.danger("证书校验已关闭", "配置包含 insecure=true，TLS 仍加密流量，但无法可靠阻止中间人伪造服务端。") else findings.pass("证书校验正常", "未发现 insecure=true。")
            if (sni.isBlank()) findings.warning("未显式配置 SNI", "如果服务端使用域名证书，建议确认 server_name/SNI 与证书一致。") else findings.pass("SNI 已配置", "TLS server_name 为 ${maskHost(sni)}。")
        }
        if (node.rawJson.isNotBlank() && raw == null) findings.warning("原始配置解析失败", "安全检查只能依据节点结构化字段，部分高级参数可能未覆盖。")
        if (findings.isEmpty()) findings.warning("信息不足", "当前节点缺少足够的可审计字段。")

        val rating = when {
            findings.any { it.severity == NodeSecuritySeverity.DANGER } -> NodeSecurityRating.HIGH_RISK
            findings.any { it.severity == NodeSecuritySeverity.WARNING } -> NodeSecurityRating.ATTENTION
            else -> NodeSecurityRating.GOOD
        }
        val title = when (rating) { NodeSecurityRating.GOOD -> "配置较安全"; NodeSecurityRating.ATTENTION -> "配置需注意"; NodeSecurityRating.HIGH_RISK -> "发现高风险配置" }
        val summary = when (rating) { NodeSecurityRating.GOOD -> "未发现明显的协议或 TLS 配置风险。"; NodeSecurityRating.ATTENTION -> "存在需要确认的安全项，但不一定代表节点不可用。"; NodeSecurityRating.HIGH_RISK -> "存在可能削弱加密或服务端身份校验的配置项。" }
        return NodeSecurityReport(rating, title, summary, findings)
    }

    private fun inspectShadowsocks(node: ProxyNode, raw: JsonObject?, findings: MutableList<NodeSecurityFinding>) {
        val method = raw?.string("method").orEmpty().ifBlank { node.ssMethod }.lowercase()
        when { method.isBlank() -> findings.warning("未识别加密方式", "无法确认 Shadowsocks cipher。"); method in WEAK_SS_METHODS -> findings.danger("弱 Shadowsocks 加密", "当前 cipher 为 $method，建议改用 AEAD/2022 系列。"); method.startsWith("2022-") || method in STRONG_SS_METHODS -> findings.pass("Shadowsocks AEAD", "当前 cipher 为 $method。"); else -> findings.warning("Shadowsocks 加密待确认", "当前 cipher 为 $method，请确认它仍受当前服务端实现支持。") }
    }

    private fun inspectAuthentication(node: ProxyNode, findings: MutableList<NodeSecurityFinding>) {
        val required = when (node.type) { ProtocolType.VLESS_REALITY, ProtocolType.VLESS_TLS, ProtocolType.VMESS_WS_ARGO, ProtocolType.VMESS_TLS, ProtocolType.HYSTERIA1, ProtocolType.HYSTERIA2, ProtocolType.TUIC_V5, ProtocolType.ANYTLS, ProtocolType.NAIVE_H2, ProtocolType.NAIVE_H3, ProtocolType.TROJAN, ProtocolType.SHADOWSOCKS -> true; else -> false }
        if (!required) return
        if (node.uuidOrPassword.isBlank()) findings.danger("认证信息缺失", "该协议通常需要 UUID/密码等认证材料，当前节点未检测到。") else findings.pass("认证信息存在", "已检测到节点认证材料；RRBOX 不在检查结果中显示具体密钥。")
    }

    private fun parseRaw(raw: String): JsonObject? = if (raw.isBlank()) null else runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
    private fun JsonObject.objectOrNull(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.string(name: String): String = runCatching { get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty() }.getOrDefault("")
    private fun JsonObject.boolean(name: String): Boolean = runCatching { val value = get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return@runCatching false; if (value.isBoolean) value.asBoolean else value.asString.trim().lowercase() in setOf("1", "true", "yes", "on") }.getOrDefault(false)
    private fun MutableList<NodeSecurityFinding>.pass(title: String, detail: String) { add(NodeSecurityFinding(title, detail, NodeSecuritySeverity.PASS)) }
    private fun MutableList<NodeSecurityFinding>.warning(title: String, detail: String) { add(NodeSecurityFinding(title, detail, NodeSecuritySeverity.WARNING)) }
    private fun MutableList<NodeSecurityFinding>.danger(title: String, detail: String) { add(NodeSecurityFinding(title, detail, NodeSecuritySeverity.DANGER)) }
    private fun maskHost(value: String): String { val text = value.trim(); return if (text.length <= 5) "***" else "${text.take(2)}***${text.takeLast(2)}" }
    private val STRONG_SS_METHODS = setOf("aes-128-gcm", "aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305")
    private val WEAK_SS_METHODS = setOf("none", "plain", "table", "rc4", "rc4-md5", "aes-128-cfb", "aes-192-cfb", "aes-256-cfb", "bf-cfb", "camellia-128-cfb", "camellia-192-cfb", "camellia-256-cfb")
}
