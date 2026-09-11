package com.rr.client.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.routing.PerAppPolicyResolver
import com.rr.client.routing.AppNodeRouting
import com.rr.client.routing.ResolvedPerAppPolicy
import com.rr.client.vpn.HevTunnelConfig
import com.rr.client.vpn.HevAppRoutingPlan
import java.security.SecureRandom
import java.util.Base64

/**
 * Converts the already-validated stable system-TUN config into the HEV data-plane form.
 *
 * The stable config remains the canonical source of truth. HEV only replaces the TUN inbound
 * with loopback SOCKS5 inbounds and extracts Android per-app policy from the removed TUN.
 * Application bindings become inlet routes backed by native original-socket ownership checks.
 * Without bindings the original single-inlet, no-owner-lookup path remains unchanged.
 * Resolver-endpoint handling is explicit because SOCKS has no System TUN DNS endpoint.
 */
object HevConfigAdapter {
    const val SOCKS_PORT = 20808
    const val SOCKS_TAG = "hev-socks-in"
    private const val SELF_PACKAGE = "com.rr.client"

    data class Runtime(
        val configJson: String,
        val perAppPolicy: ResolvedPerAppPolicy,
        val appRouting: HevAppRoutingPlan = HevAppRoutingPlan()
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

        val route = root.getAsJsonObject("route")
            ?: throw IllegalArgumentException("稳定配置缺少 route")
        val rules = route.getAsJsonArray("rules") ?: JsonArray()
        val bindingRules = rules.filter { element ->
            element.isJsonObject && isBindingRule(element.asJsonObject)
        }.map { it.asJsonObject }
        // Only packages admitted by the existing Android scope can acquire another outlet.
        val packageTargets = linkedMapOf<String, String>()
        bindingRules.forEach { rule ->
            val target = if (rule.get("action")?.asString == "reject") "reject"
                else rule.get("outbound").asString
            packages(rule).filter { isCaptured(it, policy) }.forEach { name ->
                packageTargets.putIfAbsent(name, target)
            }
        }
        val targets = packageTargets.values.distinct().sorted()
        require(targets.size <= 65535 - SOCKS_PORT) { "HEV 应用线路数量过多" }
        val targetPorts = targets.mapIndexed { index, target -> target to SOCKS_PORT + index + 1 }.toMap()
        val targetTags = targets.mapIndexed { index, target -> target to "hev-app-in-$index" }.toMap()
        val packageTags = packageTargets.mapValues { targetTags.getValue(it.value) }
        val plan = if (targets.isEmpty()) HevAppRoutingPlan() else HevAppRoutingPlan(
            packagePorts = packageTargets.mapValues { targetPorts.getValue(it.value) },
            mainTargetPorts = targetPorts["proxy"]?.let(::setOf).orEmpty(),
            socksUsername = "rrbox",
            socksPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteArray(32).also { SecureRandom().nextBytes(it) }
            )
        )
        val inboundTags = listOf(SOCKS_TAG) + targets.map { targetTags.getValue(it) }
        root.add("inbounds", JsonArray().apply {
            add(socksInbound(SOCKS_TAG, SOCKS_PORT, plan))
            targets.forEach { target ->
                add(socksInbound(targetTags.getValue(target), targetPorts.getValue(target), plan))
            }
        })

        route.add("rules", JsonArray().apply {
            // Resolve the VPN-advertised DNS endpoint on every outlet before business rules.
            add(JsonObject().apply {
                add("inbound", JsonArray().apply { inboundTags.forEach(::add) })
                add("ip_cidr", JsonArray().apply { add("${HevTunnelConfig.DNS_ADDRESS}/32") })
                addProperty("port", 53)
                add("network", JsonArray().apply { add("tcp"); add("udp") })
                addProperty("action", "hijack-dns")
            })
            rules.forEach { element ->
                val rule = element.takeIf { it.isJsonObject }?.asJsonObject
                when {
                    rule != null && AppNodeRouting.isUnknownOwnerGuard(rule) -> Unit
                    rule != null && isBindingRule(rule) -> {
                        val tags = packages(rule).mapNotNull(packageTags::get).distinct()
                        if (tags.isNotEmpty()) add(rule.deepCopy().apply {
                            remove("package_name")
                            add("inbound", JsonArray().apply { tags.forEach(::add) })
                        })
                    }
                    else -> add(element)
                }
            }
        })
        // A bound app's own DNS sockets use that same outlet/resolver. Android netd may
        // instead own shared DNS requests; those remain on the ordinary DNS policy.
        if (plan.enabled) {
            root.getAsJsonObject("dns")?.let { dns ->
                dns.getAsJsonArray("rules")?.let { dnsRules ->
                    dns.add("rules", JsonArray().apply {
                        dnsRules.forEach { element ->
                            val rule = element.takeIf { it.isJsonObject }?.asJsonObject
                            val tags = rule?.let(::packages)?.mapNotNull(packageTags::get)?.distinct().orEmpty()
                            if (rule != null && tags.isNotEmpty()) add(rule.deepCopy().apply {
                                remove("package_name")
                                add("inbound", JsonArray().apply { tags.forEach(::add) })
                            })
                            add(element)
                        }
                    })
                }
            }
        }

        return Runtime(gson.toJson(root), policy, plan)
    }

    private fun socksInbound(tag: String, port: Int, plan: HevAppRoutingPlan) = JsonObject().apply {
        addProperty("type", "socks")
        addProperty("tag", tag)
        addProperty("listen", "127.0.0.1")
        addProperty("listen_port", port)
        if (plan.enabled) add("users", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("username", plan.socksUsername)
                addProperty("password", plan.socksPassword)
            })
        })
    }

    private fun packages(rule: JsonObject): List<String> = rule.getAsJsonArray("package_name")
        ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString }.orEmpty()

    private fun isCaptured(name: String, policy: ResolvedPerAppPolicy): Boolean = when {
        name == SELF_PACKAGE -> false
        policy.allowedPackages.isNotEmpty() -> name in policy.allowedPackages
        else -> name !in policy.disallowedPackages
    }

    private fun isBindingRule(rule: JsonObject): Boolean {
        if (!rule.has("package_name") || rule.has("type")) return false
        val action = rule.get("action")?.asString
        val target = rule.get("outbound")?.asString.orEmpty()
        return action == "reject" || (action == "route" &&
            (target == "proxy" || AppNodeRouting.nodeIdFromTag(target) != null))
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
