package com.rr.client.routing

/** The same stored generation may be retried; only its current operation may finish it. */
internal object RuleUpdateIdentity {
    fun owns(activeOperation: Long, activeGeneration: String?, requestedOperation: Long, requestedGeneration: String): Boolean =
        activeGeneration != null && activeGeneration == requestedGeneration &&
            requestedOperation == activeOperation
}
