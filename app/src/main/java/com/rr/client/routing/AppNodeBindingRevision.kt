package com.rr.client.routing

import java.util.concurrent.atomic.AtomicLong

/** Process-local generation for async preparation; cache validity still compares full bindings. */
object AppNodeBindingRevision {
    private val revision = AtomicLong(0L)

    fun current(): Long = revision.get()
    fun changed(): Long = revision.incrementAndGet()
}
