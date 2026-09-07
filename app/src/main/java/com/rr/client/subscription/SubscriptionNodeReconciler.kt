package com.rr.client.subscription

import com.rr.client.core.NodeIdentity
import com.rr.client.core.model.ProxyNode
import java.util.UUID

/** Reserve exact matches before fallback; never attach an override by an incoming array index. */
object SubscriptionNodeReconciler {
    fun reconcile(previous: List<ProxyNode>, incoming: List<ProxyNode>): List<ProxyNode> {
        val exactOld = previous.groupBy(NodeIdentity::key)
        val exactNew = incoming.groupingBy(NodeIdentity::key).eachCount()
        val endpointOld = previous.groupBy(::endpoint)
        val endpointNew = incoming.groupingBy(::endpoint).eachCount()
        val exactMatches = incoming.map { fresh ->
            val key = NodeIdentity.key(fresh)
            exactOld[key]?.singleOrNull()?.takeIf { exactNew[key] == 1 }
        }
        val reserved = exactMatches.mapNotNullTo(mutableSetOf()) { it?.id }
        val used = mutableSetOf<String>()
        return incoming.mapIndexed { index, fresh ->
            val exact = exactMatches[index]
            val fallback = endpointOld[endpoint(fresh)]?.singleOrNull()
                ?.takeIf { endpointNew[endpoint(fresh)] == 1 && it.id !in reserved }
            val match = (exact ?: fallback)?.takeIf { used.add(it.id) }
            fresh.copy(id = match?.id ?: "${fresh.profileId}-${UUID.randomUUID()}")
        }
    }
    private fun endpoint(node: ProxyNode): List<String> = listOf(
        node.type.name, node.server.lowercase(), node.serverPort.toString(), node.tag
    )
}
