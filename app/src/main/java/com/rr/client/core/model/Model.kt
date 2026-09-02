package com.rr.client.core.model

enum class ProtocolType {
    VLESS_REALITY,
    VLESS_TLS,
    VMESS_WS_ARGO,
    VMESS_TLS,
    HYSTERIA2,
    TUIC_V5,
    ANYTLS,
    NAIVE_H2,
    NAIVE_H3,
    TROJAN,
    SHADOWSOCKS,
    CUSTOM
}

/** 供列表徽章展示的简短友好标签 */
fun ProtocolType.friendlyLabel(): String = when (this) {
    ProtocolType.VLESS_REALITY -> "VLESS"
    ProtocolType.VLESS_TLS -> "VLESS-TLS"
    ProtocolType.VMESS_WS_ARGO -> "VMess-WS"
    ProtocolType.VMESS_TLS -> "VMess"
    ProtocolType.HYSTERIA2 -> "HY2"
    ProtocolType.TUIC_V5 -> "TUIC"
    ProtocolType.TROJAN -> "Trojan"
    ProtocolType.SHADOWSOCKS -> "SS"
    ProtocolType.ANYTLS -> "AnyTLS"
    ProtocolType.NAIVE_H2, ProtocolType.NAIVE_H3 -> "Naive"
    ProtocolType.CUSTOM -> "自定义"
}

data class ProxyNode(
    val id: String,
    val tag: String,
    val type: ProtocolType,
    val server: String,
    val serverPort: Int,
    val uuidOrPassword: String = "",
    val flow: String = "",
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val sni: String = "",
    /** 传输层: tcp / ws / grpc（vmess、trojan、vless 用） */
    val network: String = "",
    /** ws 路径或 grpc serviceName */
    val path: String = "",
    /** ws Host 头 / HTTP 伪装域名 */
    val host: String = "",
    /** TUIC ALPN，逗号分隔 */
    val alpn: String = "",
    /** TLS 是否启用（vless/vmess/trojan 无 TLS 变体时置 false） */
    val tlsEnabled: Boolean = true,
    /** Shadowsocks 加密方式 */
    val ssMethod: String = "",
    /** Hysteria2 obfs 类型（如 salamander） */
    val obfs: String = "",
    val obfsPassword: String = "",
    val hoppingPorts: String = "",
    /** TUIC v5: 若服务端需要 uuid+password 双认证时单独存 password */
    val extraPassword: String = "",
    /** 所属订阅组 id/名称（多订阅聚合时填充） */
    val profileId: String = "",
    val profileName: String = "",
    /** RRVPS sing-box JSON 订阅节点原文，配置生成时优先透传 */
    val rawJson: String = ""
)

data class RouteRule(
    val tag: String,
    val ruleType: String, // "domain", "ip_cidr", "package_name", "rule_set"
    val values: List<String>,
    val targetOutbound: String // "proxy", "direct", "block", or specific node tag
)

data class AppRouteConfig(
    val packageName: String,
    val appName: String,
    val routeMode: String, // "PROXY_DEFAULT", "PROXY_NODE", "DIRECT", "BYPASS"
    val assignedNodeTag: String? = null
)
