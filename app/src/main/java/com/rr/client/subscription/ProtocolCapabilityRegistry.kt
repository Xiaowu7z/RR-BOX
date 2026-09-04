package com.rr.client.subscription

import com.rr.client.core.model.ProtocolType

/**
 * User-facing import/runtime capability registry.
 *
 * The goal is to describe what RRBOX can safely ingest today without pretending that every
 * protocol has a standardized share-link format. Native sing-box JSON remains the compatibility
 * escape hatch for protocols/fields that do not have a stable URI representation.
 */
data class ProtocolCapability(
    val type: ProtocolType,
    val shareLinkImport: Boolean,
    val rawJsonImport: Boolean = true,
    val clashImport: Boolean = false,
    val notes: String = ""
)

object ProtocolCapabilityRegistry {
    val entries: List<ProtocolCapability> = listOf(
        ProtocolCapability(ProtocolType.VLESS_REALITY, shareLinkImport = true, clashImport = true),
        ProtocolCapability(ProtocolType.VLESS_TLS, shareLinkImport = true, clashImport = true),
        ProtocolCapability(ProtocolType.VMESS_WS_ARGO, shareLinkImport = true, clashImport = true),
        ProtocolCapability(ProtocolType.VMESS_TLS, shareLinkImport = true, clashImport = true),
        ProtocolCapability(ProtocolType.HYSTERIA1, shareLinkImport = true, clashImport = true),
        ProtocolCapability(ProtocolType.HYSTERIA2, shareLinkImport = true, clashImport = true),
        ProtocolCapability(ProtocolType.TUIC_V5, shareLinkImport = true, clashImport = true),
        ProtocolCapability(ProtocolType.ANYTLS, shareLinkImport = true, clashImport = true),
        ProtocolCapability(ProtocolType.NAIVE_H2, shareLinkImport = true),
        ProtocolCapability(ProtocolType.NAIVE_H3, shareLinkImport = true),
        ProtocolCapability(ProtocolType.TROJAN, shareLinkImport = true, clashImport = true),
        ProtocolCapability(ProtocolType.SHADOWSOCKS, shareLinkImport = true, clashImport = true),
        ProtocolCapability(ProtocolType.SOCKS, shareLinkImport = true, clashImport = true),
        ProtocolCapability(ProtocolType.HTTP, shareLinkImport = true, clashImport = true),
        ProtocolCapability(ProtocolType.SSH, shareLinkImport = true),
        ProtocolCapability(
            ProtocolType.WIREGUARD,
            shareLinkImport = false,
            notes = "无统一安全分享链接格式；推荐导入 sing-box JSON"
        ),
        ProtocolCapability(ProtocolType.SHADOWTLS, shareLinkImport = true),
        ProtocolCapability(ProtocolType.SNELL, shareLinkImport = true, clashImport = true),
        ProtocolCapability(
            ProtocolType.TOR,
            shareLinkImport = false,
            notes = "推荐导入 sing-box JSON"
        ),
        ProtocolCapability(
            ProtocolType.CUSTOM,
            shareLinkImport = false,
            notes = "未知/新协议通过原生 sing-box outbound 透传"
        )
    )

    private val byType = entries.associateBy(ProtocolCapability::type)

    fun capability(type: ProtocolType): ProtocolCapability =
        byType[type] ?: error("Protocol capability missing for $type")

    fun shareImportLabels(): String = entries
        .filter(ProtocolCapability::shareLinkImport)
        .joinToString(" · ") { it.type.friendlyName() }

    fun rawOnlyLabels(): String = entries
        .filter { !it.shareLinkImport && it.rawJsonImport }
        .joinToString(" · ") { it.type.friendlyName() }

    private fun ProtocolType.friendlyName(): String = when (this) {
        ProtocolType.VLESS_REALITY -> "VLESS Reality"
        ProtocolType.VLESS_TLS -> "VLESS TLS"
        ProtocolType.VMESS_WS_ARGO -> "VMess WS/gRPC"
        ProtocolType.VMESS_TLS -> "VMess"
        ProtocolType.HYSTERIA1 -> "Hysteria1"
        ProtocolType.HYSTERIA2 -> "Hysteria2"
        ProtocolType.TUIC_V5 -> "TUIC v5"
        ProtocolType.ANYTLS -> "AnyTLS"
        ProtocolType.NAIVE_H2 -> "Naive H2"
        ProtocolType.NAIVE_H3 -> "Naive H3"
        ProtocolType.TROJAN -> "Trojan"
        ProtocolType.SHADOWSOCKS -> "Shadowsocks"
        ProtocolType.SOCKS -> "SOCKS"
        ProtocolType.HTTP -> "HTTP(S)"
        ProtocolType.SSH -> "SSH"
        ProtocolType.WIREGUARD -> "WireGuard"
        ProtocolType.SHADOWTLS -> "ShadowTLS"
        ProtocolType.SNELL -> "Snell"
        ProtocolType.TOR -> "Tor"
        ProtocolType.CUSTOM -> "自定义/新协议"
    }
}
