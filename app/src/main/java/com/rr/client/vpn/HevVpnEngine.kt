package com.rr.client.vpn

import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.rr.client.core.HevConfigAdapter
import com.rr.client.routing.ResolvedPerAppPolicy
import java.io.File
import java.net.InetAddress

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

    fun start(
        policy: ResolvedPerAppPolicy,
        includeSelfForBenchmark: Boolean = false
    ): Boolean {
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
                .setSession(if (includeSelfForBenchmark) "RRBOX · HEV · A/B" else "RRBOX · HEV")
                .setMtu(HevTunnelConfig.MTU)
                .addAddress(HevTunnelConfig.IPV4_CLIENT, HevTunnelConfig.IPV4_PREFIX)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(HevTunnelConfig.MAPPED_DNS)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            if (includeSelfForBenchmark) {
                require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    "HEV A/B v2.1 路径校验需要 Android 13 或更高版本"
                }
                // Benchmark-only routing: RRBOX's own UID must enter the HEV TUN so the
                // active probes measure TUN -> lwIP -> SOCKS -> sing-box. Keep 127/8 outside
                // the VPN so HEV's local SOCKS hop can never feed back into its own TUN.
                builder.excludeRoute(IpPrefix(InetAddress.getByName("127.0.0.0"), 8))
                onLog("HEV A/B v2.1：RRBOX 自身临时纳入 TUN，127/8 保持本地回环")
            }

            applyPerAppPolicy(builder, policy, includeSelfForBenchmark)

            val pfd = builder.establish()
                ?: error("Android 拒绝建立 HEV VPN 接口")
            tunPfd = pfd
            onLog("HEV Android TUN 已建立，fd=${pfd.fd}, mtu=${HevTunnelConfig.MTU}, IPv4-only")

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
        policy: ResolvedPerAppPolicy,
        includeSelfForBenchmark: Boolean
    ) {
        when {
            policy.allowedPackages.isNotEmpty() -> {
                var added = 0
                val allowed = if (includeSelfForBenchmark) {
                    policy.allowedPackages + SELF_PACKAGE
                } else {
                    policy.allowedPackages.filterNot { it == SELF_PACKAGE }
                }
                allowed
                    .asSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .forEach { packageName ->
                        runCatching { builder.addAllowedApplication(packageName) }
                            .onSuccess {
                                added++
                                onLog(
                                    if (packageName == SELF_PACKAGE && includeSelfForBenchmark) {
                                        "HEV A/B v2.1 临时纳入 RRBOX 自身 UID"
                                    } else {
                                        "HEV 仅选中代理：$packageName"
                                    }
                                )
                            }
                            .onFailure { error ->
                                Log.w(TAG, "Unable to allow package $packageName", error)
                            }
                    }
                require(added > 0) { "HEV 仅选中代理没有可用的已安装应用" }
            }

            policy.disallowedPackages.isNotEmpty() -> {
                val disallowed = if (includeSelfForBenchmark) {
                    policy.disallowedPackages.filterNot { it == SELF_PACKAGE }
                } else {
                    policy.disallowedPackages + SELF_PACKAGE
                }
                disallowed
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
                if (includeSelfForBenchmark) {
                    onLog("HEV A/B v2.1：保留用户绕过列表，但 RRBOX 自身不再绕过")
                }
            }

            else -> {
                if (includeSelfForBenchmark) {
                    onLog("HEV A/B v2.1：全部代理模式下 RRBOX 自身临时进入 HEV TUN")
                } else {
                    // Normal HEV mode keeps RRBOX itself outside the VPN. This behavior is
                    // intentionally unchanged; only Network Lab's transient benchmark restart
                    // is allowed to include self, with 127/8 explicitly excluded above.
                    builder.addDisallowedApplication(SELF_PACKAGE)
                    onLog("HEV 全部代理：RRBOX 自身保持 VPN 外以避免回环")
                }
            }
        }
    }

    private fun fail(message: String): Boolean {
        lastError = message
        onLog("HEV 启动失败：$message")
        return false
    }
}
