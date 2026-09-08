package com.rr.client.routing

/**
 * A schema-1 PROXY group that older APKs can read as an ordinary routing rule.
 * New APKs additionally recover only these exact X hosts from a stale public destination.
 * The remotely maintained data never supplies an action, resolver or replacement host.
 */
object XDestinationRecoveryPolicy {
    const val RULE_ID = "x-destination-recovery"
    const val MAX_HOSTS = 64

    private val serviceRoots = listOf("twitter.com", "x.com", "twimg.com", "t.co", "tweetdeck.com")
    private val observedHosts = listOf(
        "abs.twimg.com", "ads-api.x.com", "analytics.twitter.com", "api-stream.twitter.com",
        "api.twitter.com", "api.x.com", "pbs.twimg.com", "probe.twitter.com", "video.twimg.com", "x.com"
    )

    // ip_is_private is core-defined. Also protect non-public/synthetic pools explicitly,
    // notably stale HEV mapped addresses, benchmark pools, multicast and IPv6 translators.
    val excludedDestinationCidrs: List<String> = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
        "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.168.0.0/16",
        "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4",
        "::/128", "::1/128", "::ffff:0:0/96", "64:ff9b::/96", "64:ff9b:1::/48",
        "100::/64", "2001::/32", "2001:db8::/32", "2002::/16", "fc00::/7", "fe80::/10", "ff00::/8"
    )

    fun bundledRule() = DomesticRoutingPolicy.DomainRule(
        RULE_ID, DomesticRoutingPolicy.Destination.PROXY, emptyList(), observedHosts
    )

    fun validate(rule: DomesticRoutingPolicy.DomainRule) {
        if (rule.id != RULE_ID) return
        require(rule.destination == DomesticRoutingPolicy.Destination.PROXY && rule.suffixes.isEmpty()) {
            "X 地址恢复仅允许精确海外域名"
        }
        require(rule.domains.size in 1..MAX_HOSTS && rule.domains.all { host ->
            serviceRoots.any { root -> host == root || host.endsWith(".$root") }
        }) { "X 地址恢复包含超出范围的域名" }
    }

    fun hosts(policy: RoutingPolicySnapshot, vararg bootstrapHosts: String): List<String> {
        val rule = policy.domainRules.firstOrNull { it.id == RULE_ID } ?: return emptyList()
        validate(rule)
        val bootstrap = bootstrapHosts.map { it.lowercase().trimEnd('.') }.toSet()
        return rule.domains.filter { host ->
            host !in bootstrap && policy.domainRules.firstOrNull { candidate ->
                host in candidate.domains || candidate.suffixes.any { host == it || host.endsWith(".$it") }
            }?.destination == DomesticRoutingPolicy.Destination.PROXY
        }
    }
}
