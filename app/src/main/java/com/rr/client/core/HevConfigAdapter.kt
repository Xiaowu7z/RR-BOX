package com.rr.client.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.routing.PerAppPolicyResolver
import com.rr.client.routing.ResolvedPerAppPolicy
import com.rr.client.vpn.HevTunnelConfig

/**
 * Converts the already-validated stable system-TUN config into the HEV data-plane form.
 *
 * The stable config remains the canonical source of truth. HEV only replaces the TUN inbound
 * with a loopback SOCKS5 inbound and extracts Android per-app policy from the removed TUN.
 * Business route/DNS/outbound policies are preserved. One narrow resolver-endpoint rule is
 * prepended because SOCKS does not have the System TUN's built-in DNS endpoint handling.
 */
object HevConfigAdapter {
    const val SOCKS_PORT = 20808
    const val SOCKS_TAG = "hev-socks-in"
    private const val SELF_PACKAGE = "com.rr.client"

    data class Runtime(
        val configJson: String,
        val perAppPolicy: ResolvedPerAppPolicy
    )

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun adapt(stableConfigJson: String): Runtime {
        val root = JsonParser.parseString(stableConfigJson).asJsonObject.deepCopy()
        val inbounds = root.getAsJsonArray("inbounds")
            ?: throw IllegalArgumentException("稳定配置缺少 inbounds")
        val tun = inbounds.firstOrNull { element ->
            element.isJsonObject && element.asJsonObject.get("type")?.asString == "tun"
        }?.asJsonObject ?: throw IllegalArgumentException("稳定配置缺少 TUN inbound")

        val policy = extractPolicy(tun)

        root.add("inbounds", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "socks")
                addProperty("tag", SOCKS_TAG)
                addProperty("listen", "127.0.0.1")
                addProperty("listen_port", SOCKS_PORT)
            })
        })

        val route = root.getAsJsonObject("route")
            ?: throw IllegalArgumentException("稳定配置缺少 route")
        val rules = route.getAsJsonArray("rules") ?: JsonArray()
        route.add("rules", JsonArray().apply {
            // This is the DNS server advertised by our VpnService, not a general port-53
            // redirect. Keep it working even when protocol sniffing / DNS interception is off.
            // Resolve before private-IP or business rules, for both UDP and TCP fallback.
            add(JsonObject().apply {
                add("inbound", JsonArray().apply { add(SOCKS_TAG) })
                add("ip_cidr", JsonArray().apply { add("${HevTunnelConfig.DNS_ADDRESS}/32") })
                addProperty("port", 53)
                add("network", JsonArray().apply { add("tcp"); add("udp") })
                addProperty("action", "hijack-dns")
            })
            rules.forEach(::add)
        })

        return Runtime(
            configJson = gson.toJson(root),
            perAppPolicy = policy
        )
    }

    private fun extractPolicy(tun: JsonObject): ResolvedPerAppPolicy {
        val include = tun.getAsJsonArray("include_package")
            ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString }
            .orEmpty()
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot { it == SELF_PACKAGE }
            .distinct()
            .sorted()
            .toList()

        val exclude = tun.getAsJsonArray("exclude_package")
            ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString }
            .orEmpty()
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot { it == SELF_PACKAGE }
            .distinct()
            .sorted()
            .toList()

        if (include.isNotEmpty() && exclude.isNotEmpty()) {
            throw IllegalArgumentException("稳定配置同时包含 include_package 与 exclude_package")
        }
        // An explicit allow-list must remain an allow-list after excluding the bridge
        // app. Never broaden an empty / self-only selection into all-app interception.
        require(!tun.has("include_package") || include.isNotEmpty()) {
            "HEV 仅选中代理模式至少需要选择 1 个其他应用"
        }

        return when {
            include.isNotEmpty() -> ResolvedPerAppPolicy(allowedPackages = include)
            exclude.isNotEmpty() -> ResolvedPerAppPolicy(disallowedPackages = exclude)
            else -> PerAppPolicyResolver.resolve(PerAppPolicyResolver.MODE_ALL, emptySet())
        }
    }
}
