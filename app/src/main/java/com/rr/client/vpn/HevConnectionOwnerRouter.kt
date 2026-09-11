package com.rr.client.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.rr.client.core.HevConfigAdapter
import java.net.InetSocketAddress

/** Queries the original Android socket before HEV replaces its owner with the RRBOX UID. */
@RequiresApi(Build.VERSION_CODES.Q)
class HevConnectionOwnerRouter(
    private val vpnService: VpnService,
    plan: HevAppRoutingPlan,
    private val onLog: (String) -> Unit
) {
    private val connectivity = vpnService.getSystemService(ConnectivityManager::class.java)
    private val packagePorts = plan.packagePorts.toMap()
    private val mainTargetPorts = plan.mainTargetPorts.toSet()
    private val handler = Handler(Looper.getMainLooper())
    // Null closes routing during a package/UID change. An old mapping is never used while
    // an uninstall/reinstall may have reassigned its UID to a different application.
    @Volatile private var snapshot: OwnerSnapshot? = null
    @Volatile private var closed = false
    private var generation = 0
    private var lastUnknownLogAt = -30_000L
    private var receiverRegistered = false
    private val retryRefresh = Runnable { refreshPackages(failOnConflict = false) }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action in PACKAGE_ACTIONS) refreshPackages(failOnConflict = false)
        }
    }

    init {
        try {
            ContextCompat.registerReceiver(vpnService, packageReceiver, IntentFilter().apply {
                PACKAGE_ACTIONS.forEach(::addAction)
                addDataScheme("package")
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            refreshPackages(failOnConflict = true)
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    @Synchronized
    private fun refreshPackages(failOnConflict: Boolean) {
        if (closed) return
        snapshot = null
        handler.removeCallbacks(retryRefresh)
        try {
            val ports = mutableMapOf<Int, Int>()
            packagePorts.forEach { (packageName, port) ->
                val uid = try {
                    vpnService.packageManager.getApplicationInfo(packageName, 0).uid
                } catch (_: PackageManager.NameNotFoundException) {
                    return@forEach
                }
                val previous = ports.put(uid, port)
                if (previous != null && previous != port) {
                    val message = "共享 UID 的应用设置了不同节点，请将这些应用设为同一条线路：$packageName"
                    if (failOnConflict) error(message)
                    // A package installed while connected can introduce a new conflict.
                    // Block just this shared UID while other applications keep working.
                    ports[uid] = -1
                    onLog("HEV 应用线路：$message")
                }
            }
            ports.toMap().forEach { (uid, port) ->
                if (port < 1 || port in mainTargetPorts) return@forEach
                val siblings = vpnService.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
                if (siblings.isEmpty() || siblings.any { packagePorts[it] != port }) {
                    val message = "同一 UID 的应用必须一起设置相同节点，请检查：${siblings.joinToString()}"
                    if (failOnConflict) error(message)
                    ports[uid] = -1
                    onLog("HEV 应用线路：$message")
                }
            }
            val knownAppUids = vpnService.packageManager.getInstalledApplications(0)
                .mapTo(mutableSetOf()) { it.uid }
            generation = (generation % 65535) + 1
            snapshot = OwnerSnapshot(ports.toMap(), knownAppUids, generation)
        } catch (error: Exception) {
            if (failOnConflict) throw error
            // Transient package-manager failures recover automatically; never publish an
            // empty fallback map, which would send bound apps through the main outlet.
            rejectUnknown()
            handler.postDelayed(retryRefresh, 1_000L)
        }
    }

    /** Positive packed UID/generation/port; -1 always rejects, never the default route. */
    fun resolve(
        protocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): Long {
        val current = snapshot ?: return rejectUnknown()
        if (protocol != 6 && protocol != 17) return rejectUnknown()
        if (sourcePort !in 1..65535 || destinationPort !in 1..65535) return rejectUnknown()
        // parseNumericAddress never performs DNS on the single native worker.
        val uid = try {
            connectivity.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(InetAddresses.parseNumericAddress(sourceAddress), sourcePort),
                InetSocketAddress(InetAddresses.parseNumericAddress(destinationAddress), destinationPort)
            )
        } catch (_: Exception) {
            return rejectUnknown()
        }
        if (uid < 0 || snapshot !== current) return rejectUnknown()
        // Shared netd DNS is handled before application rules in every engine. Isolated
        // or otherwise unidentifiable UIDs must not send business traffic via the main node.
        if (uid !in current.knownAppUids && destinationPort != 53) return rejectUnknown()
        val port = current.uidPorts[uid] ?: HevConfigAdapter.SOCKS_PORT
        if (port < 1) return rejectUnknown()
        return HevOwnerRoute.pack(uid, port, current.generation)
    }

    @Synchronized
    fun close() {
        closed = true
        snapshot = null
        handler.removeCallbacks(retryRefresh)
        if (receiverRegistered) {
            runCatching { vpnService.unregisterReceiver(packageReceiver) }
            receiverRegistered = false
        }
    }

    private fun rejectUnknown(): Long {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUnknownLogAt >= 30_000L) {
            lastUnknownLogAt = now
            onLog("HEV 应用线路：暂时无法确认连接所属应用，已阻止该连接；不会切换到主节点")
        }
        return -1L
    }

    private data class OwnerSnapshot(
        val uidPorts: Map<Int, Int>, val knownAppUids: Set<Int>, val generation: Int
    )

    companion object {
        private val PACKAGE_ACTIONS = setOf(Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED, Intent.ACTION_PACKAGE_REPLACED)
    }
}
