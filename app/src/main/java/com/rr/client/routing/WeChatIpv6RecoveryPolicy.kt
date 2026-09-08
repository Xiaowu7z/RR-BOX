package com.rr.client.routing

/**
 * APK-owned TCP compatibility for exact WeChat endpoints observed during a Wi-Fi IPv6 failure.
 * Existing schema-1 policies still decide DIRECT/PROXY. No new rule download or rule-schema
 * action is needed, and these names never grant whole-package or whole-suffix direct routing.
 */
object WeChatIpv6RecoveryPolicy {
    const val PACKAGE_NAME = "com.tencent.mm"

    private val observedHosts = listOf(
        "szextshort.weixin.qq.com", "wx.qlogo.cn", "szshort.weixin.qq.com",
        "dns.weixin.qq.com.cn", "szminorshort.weixin.qq.com", "dldir1.qq.com",
        "c2c.cdn.weixin.qq.com", "snsqpic.cdn.weixin.qq.com", "szshort.pay.weixin.qq.com",
        "szlong.weixin.qq.com", "paydns.wechatpay.cn", "szshort.mixpay.wechatpay.cn",
        "sni.cdn.weixin.qq.com"
    )

    internal fun isReviewedHost(host: String): Boolean = host in observedHosts

    // Keep original private, fake/mapped, translator and special destinations untouched.
    // Include the IPv6 benchmark/ORCHID/documentation pools beyond the shared X guards.
    val excludedDestinationCidrs = XDestinationRecoveryPolicy.excludedDestinationCidrs + listOf(
        "2001:2::/48", "2001:10::/28", "2001:20::/28", "3fff::/20"
    )

    fun hosts(policy: RoutingPolicySnapshot, vararg bootstrapHosts: String): List<String> {
        val bootstrap = bootstrapHosts.map { it.trim().lowercase().trimEnd('.') }.toSet()
        return observedHosts.filter { host ->
            host !in bootstrap && policy.domainRules.firstOrNull { rule ->
                host in rule.domains || rule.suffixes.any { host == it || host.endsWith(".$it") }
            }?.destination == DomesticRoutingPolicy.Destination.DIRECT
        }
    }
}
