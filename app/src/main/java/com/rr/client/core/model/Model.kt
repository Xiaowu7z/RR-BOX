package com.rr.client.core.model

import com.google.gson.annotations.SerializedName

enum class ProtocolType {
    VLESS_REALITY,
    VMESS_WS_ARGO,
    VMESS_TLS,
    HYSTERIA2,
    TUIC_V5,
    ANYTLS,
    NAIVE_H2,
    NAIVE_H3,
    CUSTOM
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
    val path: String = "",
    val host: String = "",
    val hoppingPorts: String = "",
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
