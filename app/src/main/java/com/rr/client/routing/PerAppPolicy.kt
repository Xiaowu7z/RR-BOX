package com.rr.client.routing

data class ResolvedPerAppPolicy(
    val allowedPackages: List<String> = emptyList(),
    val disallowedPackages: List<String> = emptyList()
)

object PerAppPolicyResolver {
    const val MODE_ALL = "ALL"
    const val MODE_ALLOW_LIST = "ALLOW_LIST"
    const val MODE_DISALLOW_LIST = "DISALLOW_LIST"

    fun resolve(
        mode: String,
        selectedPackages: Set<String>,
        selfPackage: String = ""
    ): ResolvedPerAppPolicy {
        val selected = selectedPackages.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot { selfPackage.isNotBlank() && it == selfPackage }
            .distinct()
            .sorted()
            .toList()

        return when (mode) {
            MODE_ALL -> ResolvedPerAppPolicy()
            MODE_ALLOW_LIST -> {
                require(selected.isNotEmpty()) { "仅选中代理模式至少需要选择 1 个应用" }
                ResolvedPerAppPolicy(allowedPackages = selected)
            }
            MODE_DISALLOW_LIST -> ResolvedPerAppPolicy(disallowedPackages = selected)
            else -> throw IllegalArgumentException("未知分应用模式：$mode")
        }
    }
}
