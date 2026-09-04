package com.rr.client.lab

/** Pure policy kept separate so recovery safety rules are unit-testable. */
object NetworkRecoveryPolicy {
    fun shouldRecover(
        hadPhysicalPath: Boolean,
        desiredRunning: Boolean,
        stateStarting: Boolean,
        dataPlaneHealthy: Boolean,
        vpnPermissionReady: Boolean,
        cooldownReady: Boolean
    ): Boolean = hadPhysicalPath &&
        desiredRunning &&
        !stateStarting &&
        !dataPlaneHealthy &&
        vpnPermissionReady &&
        cooldownReady
}
