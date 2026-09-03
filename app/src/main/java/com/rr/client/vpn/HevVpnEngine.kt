package com.rr.client.vpn

import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.rr.client.core.HevConfigAdapter
import com.rr.client.routing.ResolvedPerAppPolicy
import java.io.File

/**
 * Android VpnService + HEV native TUN-to-SOCKS data plane.
 *
 * Unlike the stable engine, sing-box does not own the TUN fd here. Android creates the fd,
 * HEV consumes packets in native lwIP, and forwards them to sing-box's loopback SOCKS inbound.
 */
class HevVpnEngine(
    private val vpnService: VpnService,
    private val workingDir: File,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "HevVpnEngine"
        private const val SELF_PACKAGE = "com.rr.client"
    }

    private var tunPfd: ParcelFileDescriptor? = null

    @Volatile
    var lastError: String? = null
        private set

    val isRunning: Boolean
        get() = tunPfd != null && HevTunnelNative.isRunning()

    fun start(policy: ResolvedPerAppPolicy): Boolean {
        stop()
        lastError = null

        if (VpnService.prepare(vpnService) != null) {
            return fail("VPN 权限尚未授权")
        }
        if (!HevTunnelNative.ensureLoaded()) {
            return fail("HEV native 库未包含在当前 APK 中")
        }

        return runCatching {
            val builder = vpnService.Builder()
                .setSession("RRBOX · HEV")
                .setMtu(HevTunnelConfig.MTU)
                .addAddress(HevTunnelConfig.IPV4_CLIENT, HevTunnelConfig.IPV4_PREFIX)
                .addAddress(HevTunnelConfig.IPV6_CLIENT, HevTunnelConfig.IPV6_PREFIX)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer(HevTunnelConfig.MAPPED_DNS)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            applyPerAppPolicy(builder, policy)

            val pfd = builder.establish()
                ?: error("Android 拒绝建立 HEV VPN 接口")
            tunPfd = pfd
            onLog("HEV Android TUN 已建立，fd=${pfd.fd}, mtu=${HevTunnelConfig.MTU}")

            workingDir.mkdirs()
            val configFile = File(workingDir, "hev-socks5-tunnel.yaml")
            configFile.writeText(HevTunnelConfig.build(HevConfigAdapter.SOCKS_PORT))

            check(HevTunnelNative.start(configFile.absolutePath, pfd.fd)) {
                "HEV native 线程启动失败"
            }

            // JNI returns after the worker thread is created. Give config/lwIP initialization a
            // small window so malformed/native startup failures are detected before reporting UP.
            Thread.sleep(80L)
            check(HevTunnelNative.isRunning()) {
                "HEV native 初始化后立即退出"
            }

            onLog("HEV native 极速数据面已启动：TUN → lwIP → SOCKS5 → sing-box")
            true
        }.getOrElse { error ->
            Log.e(TAG, "Unable to start HEV engine", error)
            val message = error.message ?: error.javaClass.simpleName
            stop()
            fail(message)
        }
    }

    fun stop() {
        runCatching { HevTunnelNative.stop() }
            .onFailure { Log.w(TAG, "Unable to stop HEV native worker", it) }
        runCatching { tunPfd?.close() }
            .onFailure { Log.w(TAG, "Unable to close HEV TUN fd", it) }
        tunPfd = null
    }

    private fun applyPerAppPolicy(
        builder: VpnService.Builder,
        policy: ResolvedPerAppPolicy
    ) {
        when {
            policy.allowedPackages.isNotEmpty() -> {
                var added = 0
                policy.allowedPackages
                    .asSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .filterNot { it == SELF_PACKAGE }
                    .distinct()
                    .forEach { packageName ->
                        runCatching { builder.addAllowedApplication(packageName) }
                            .onSuccess {
                                added++
                                onLog("HEV 仅选中代理：$packageName")
                            }
                            .onFailure { error ->
                                Log.w(TAG, "Unable to allow package $packageName", error)
                            }
                    }
                require(added > 0) { "HEV 仅选中代理没有可用的已安装应用" }
            }

            policy.disallowedPackages.isNotEmpty() -> {
                (policy.disallowedPackages + SELF_PACKAGE)
                    .asSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .forEach { packageName ->
                        runCatching { builder.addDisallowedApplication(packageName) }
                            .onSuccess { onLog("HEV 绕过 VPN：$packageName") }
                            .onFailure { error ->
                                Log.w(TAG, "Unable to bypass package $packageName", error)
                            }
                    }
            }

            else -> {
                // HEV connects to a local SOCKS listener from RRBOX's own UID. Keep self outside
                // the VPN to prevent the bridge/core control traffic from re-entering the TUN.
                builder.addDisallowedApplication(SELF_PACKAGE)
                onLog("HEV 全部代理：RRBOX 自身保持 VPN 外以避免回环")
            }
        }
    }

    private fun fail(message: String): Boolean {
        lastError = message
        onLog("HEV 启动失败：$message")
        return false
    }
}
