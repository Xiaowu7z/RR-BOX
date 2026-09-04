package com.rr.client.lab

/** A/B v2.7 accounting policy. Counters are path validators, not byte-perfect payload meters. */
internal object AccountingPolicy {
    const val MIN_WAIT_MILLIS = 1_200L
    const val MAX_WAIT_MILLIS = 3_500L
    const val POLL_MILLIS = 100L
    const val REQUIRED_PERCENT = 80L
}
