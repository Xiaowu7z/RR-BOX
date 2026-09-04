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
 * Normal HEV keeps RRBOX itself outside the VPN because HEV talks to sing-box through a local
 * loopback SOCKS inbound. Network Lab has one narrow exception: during the transient HEV benchmark
 * restart, RRBOX itself is allowed into the TUN so the benchmark socket follows the exact HEV data
 * path. We do not alter 127/8 routing. sing-box remote sockets remain protected by
 * BoxServiceWrapper.autoDetectInterfaceControl(fd) -> VpnService.protect(fd).
 *
 * A/B v2.8 additionally enables a benchmark-only HEV SOCKS handshake latency candidate
 * (pipeline + best-effort TCP Fast Open). Normal HEV keeps the proven production profile.
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
            val latencyCandidate = includeSelfForBenchmark
            val builder = vpnService.Builder()
                .setSession(
                    if (includeSelfForBenchmark) {
                        "RRBOX · HEV · A/B v2.8 candidate"
                    } else {
                        "RRBOX · HEV"
                    }
                )
                .setMtu(HevTunnelConfig.MTU)
                .addAddress(HevTunnelConfig.IPV4_CLIENT, HevTunnelConfig.IPV4_PREFIX)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(HevTunnelConfig.MAPPED_DNS)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            applyPerAppPolicy(builder, policy, includeSelfForBenchmark)

            val pfd = builder.establish()
                ?: error("Android 拒绝建立 HEV VPN 接口")
            tunPfd = pfd
            onLog("HEV Android TUN 已建立，fd=${pfd.fd}, mtu=${HevTunnelConfig.MTU}, IPv4-only")

            workingDir.mkdirs()
            val configFile = File(workingDir, "hev-socks5-tunnel.yaml")
            configFile.writeText(
                HevTunnelConfig.build(
                    socksPort = HevConfigAdapter.SOCKS_PORT,
                    latencyCandidate = latencyCandidate
                )
            )

            check(HevTunnelNative.start(configFile.absolutePath, pfd.fd)) {
                "HEV native 线程启动失败"
            }

            Thread.sleep(80L)
            check(HevTunnelNative.isRunning()) {
                "HEV native 初始化后立即退出"
            }

            if (includeSelfForBenchmark) {
                onLog(
                    "HEV A/B v2.8：RRBOX UID 临时进入 TUN；127/8 保持系统 loopback；" +
                        "sing-box 远端 socket 继续由 protect(fd) 绕过 VPN"
                )
                onLog(
                    "HEV A/B v2.8 latency candidate：SOCKS5 pipeline=true；" +
                        "tcp-fastopen=true（best-effort，系统不支持时回退普通 TCP）"
                )
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
                                if (packageName == SELF_PACKAGE && includeSelfForBenchmark) {
                                    onLog("HEV A/B 临时纳入 RRBOX 自身 UID")
                                } else {
                                    onLog("HEV 仅选中代理：$packageName")
                                }
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
                    onLog("HEV A/B：保留用户绕过列表，但 RRBOX 自身临时不绕过")
                }
            }

            else -> {
                if (includeSelfForBenchmark) {
                    onLog("HEV A/B：全部代理模式临时允许 RRBOX 自身进入 TUN")
                } else {
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
