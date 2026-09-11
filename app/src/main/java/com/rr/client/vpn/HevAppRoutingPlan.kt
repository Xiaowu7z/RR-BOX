package com.rr.client.vpn

/** Immutable for one native worker lifetime; changes require stopping its existing sessions. */
data class HevAppRoutingPlan(
    val packagePorts: Map<String, Int> = emptyMap(),
    val mainTargetPorts: Set<Int> = emptySet(),
    val socksUsername: String? = null,
    val socksPassword: String? = null
) {
    val enabled: Boolean get() = packagePorts.isNotEmpty()
}

/** Native compares all 64 bits, so a recycled UID also gets a new association generation. */
internal object HevOwnerRoute {
    fun pack(uid: Int, port: Int, generation: Int = 0): Long {
        require(uid >= 0 && port in 1..65535 && generation in 0..65535)
        return (uid.toLong() shl 32) or (generation.toLong() shl 16) or port.toLong()
    }
}
