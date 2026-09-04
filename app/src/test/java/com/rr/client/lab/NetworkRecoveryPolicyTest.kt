package com.rr.client.lab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRecoveryPolicyTest {
    @Test
    fun recoversOnlyAfterRealHandoffWithDesiredDeadDataPlane() {
        assertTrue(
            NetworkRecoveryPolicy.shouldRecover(
                hadPhysicalPath = true,
                desiredRunning = true,
                stateStarting = false,
                dataPlaneHealthy = false,
                vpnPermissionReady = true,
                cooldownReady = true
            )
        )
    }

    @Test
    fun manualStopNeverReconnects() {
        assertFalse(
            NetworkRecoveryPolicy.shouldRecover(
                hadPhysicalPath = true,
                desiredRunning = false,
                stateStarting = false,
                dataPlaneHealthy = false,
                vpnPermissionReady = true,
                cooldownReady = true
            )
        )
    }

    @Test
    fun healthyStartingOrPermissionFailureNeverTriggersRecovery() {
        assertFalse(
            NetworkRecoveryPolicy.shouldRecover(
                hadPhysicalPath = true,
                desiredRunning = true,
                stateStarting = false,
                dataPlaneHealthy = true,
                vpnPermissionReady = true,
                cooldownReady = true
            )
        )
        assertFalse(
            NetworkRecoveryPolicy.shouldRecover(
                hadPhysicalPath = true,
                desiredRunning = true,
                stateStarting = true,
                dataPlaneHealthy = false,
                vpnPermissionReady = true,
                cooldownReady = true
            )
        )
        assertFalse(
            NetworkRecoveryPolicy.shouldRecover(
                hadPhysicalPath = true,
                desiredRunning = true,
                stateStarting = false,
                dataPlaneHealthy = false,
                vpnPermissionReady = false,
                cooldownReady = true
            )
        )
    }

    @Test
    fun cooldownSuppressesRepeatedRecovery() {
        assertFalse(
            NetworkRecoveryPolicy.shouldRecover(
                hadPhysicalPath = true,
                desiredRunning = true,
                stateStarting = false,
                dataPlaneHealthy = false,
                vpnPermissionReady = true,
                cooldownReady = false
            )
        )
    }
}
