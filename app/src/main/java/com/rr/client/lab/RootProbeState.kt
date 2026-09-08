package com.rr.client.lab

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.system.Os
import com.rr.client.BuildConfig
import com.rr.client.vpn.RRVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

internal data class RootProbeReport(
    val startedAtMillis: Long,
    val selectedEngine: String,
    val vpnBefore: String,
    val activeEngineBefore: String,
    val networkBefore: String,
    val vpnAfter: String,
    val activeEngineAfter: String,
    val networkAfter: String,
    val appNetns: String,
    val execution: RootProbeExecution
) {
    fun toPlainText(): String = buildString {
        appendLine("RRBOX Root 隔离测试报告")
        appendLine("apk_version=${BuildConfig.VERSION_NAME}")
        appendLine("apk_version_code=${BuildConfig.VERSION_CODE}")
        appendLine("package=${BuildConfig.APPLICATION_ID}")
        appendLine("root_probe_protocol_version=1")
        appendLine("app_uid=${Process.myUid()}")
        appendLine("app_netns=$appNetns")
        appendLine("started_at=${Instant.ofEpochMilli(startedAtMillis)}")
        appendLine("elapsed_ms=${execution.elapsedMillis}")
        appendLine("selected_engine_before=$selectedEngine")
        appendLine("vpn_before=$vpnBefore")
        appendLine("active_engine_before=$activeEngineBefore")
        appendLine("default_network_before=$networkBefore")
        appendLine("vpn_after=$vpnAfter")
        appendLine("active_engine_after=$activeEngineAfter")
        appendLine("default_network_after=$networkAfter")
        appendLine("host_result=${execution.outcome}")
        appendLine("native_report_complete=${execution.complete}")
        appendLine("exit_code=${execution.exitCode ?: "UNKNOWN"}")
        appendLine("host_timeout=${execution.timedOut}")
        appendLine("launch_error=${execution.launchError ?: "NONE"}")
        appendLine("scope=仅检查 Root 权限、临时 TUN 与清理；不会切换当前 System / HEV 或接管流量")
        appendLine("interpretation=隔离检查通过只代表 UID 0 和临时 TUN 创建、保持 down/无地址、关闭清理通过。")
        appendLine("limitations=未验证公网、TLS、HTTPS、应用流量、透明转发或应用 UID 映射；可选能力分别报告。")
        appendLine("privacy=仅保留本进程最近一份报告；仅在用户选择文件后导出，无网络上传。")
        appendLine()
        appendOutput("stdout", execution.stdout)
        appendLine()
        appendOutput("stderr", execution.stderr)
    }

    private fun StringBuilder.appendOutput(name: String, output: RootProbeOutput) {
        appendLine("${name}_finished=${output.finished}")
        appendLine("${name}_truncated=${output.truncated}")
        appendLine("${name}_read_error=${output.error ?: "NONE"}")
        appendLine("--- native $name (最多 65536 字节) ---")
        append(output.text)
        if (!output.text.endsWith('\n')) appendLine()
        if (output.truncated) appendLine("[输出已截断；无法判定完整通过]")
    }
}

internal data class RootProbeUiState(
    val busy: Boolean = false,
    val report: RootProbeReport? = null,
    val exportPending: Boolean = false,
    val exportMessage: String? = null
)

/** Process-scoped ownership keeps rotation, tab changes and navigation from abandoning a probe. */
internal object RootProbeState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(RootProbeUiState())
    val state = mutableState.asStateFlow()
    private val exportLock = Any()
    private var pendingExport: String? = null

    fun start(context: Context, selectedEngine: String) {
        if (!running.compareAndSet(false, true)) return
        val application = context.applicationContext
        mutableState.update { it.copy(busy = true, exportMessage = null) }
        scope.launch {
            val startedAt = System.currentTimeMillis()
            val before = vpnSnapshot()
            val networkBefore = networkSnapshot(application)
            val appNetns = runCatching { Os.readlink("/proc/self/ns/net") }.getOrDefault("UNKNOWN")
            val result = try {
                val binary = File(application.applicationInfo.nativeLibraryDir, RootProbeCommand.BINARY_NAME)
                if (!binary.isFile) {
                    RootProbeExecution(launchError = "APK 中未找到打包的 Root 隔离探针")
                } else {
                    RootProbeProcessRunner(elapsedRealtimeMillis = SystemClock::elapsedRealtime).run(binary.absolutePath)
                }
            } catch (cancelled: CancellationException) {
                // The process runner has already terminated its owned process in finally.
                mutableState.update { it.copy(busy = false) }
                running.set(false)
                throw cancelled
            } catch (error: Exception) {
                RootProbeExecution(launchError = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(1024))
            }
            val after = vpnSnapshot()
            val report = RootProbeReport(
                startedAt, selectedEngine, before.first, before.second, networkBefore,
                after.first, after.second, networkSnapshot(application), appNetns, result
            )
            mutableState.update { it.copy(busy = false, report = report) }
            running.set(false)
        }
    }

    fun prepareExport(): String? = synchronized(exportLock) {
        if (mutableState.value.exportPending) return@synchronized null
        val report = mutableState.value.report ?: return@synchronized null
        pendingExport = report.toPlainText()
        mutableState.update { it.copy(exportPending = true, exportMessage = null) }
        "RRBOX-root-probe-${report.startedAtMillis}.txt"
    }

    fun exportLaunchFailed() = synchronized(exportLock) {
        pendingExport = null
        mutableState.update { it.copy(exportPending = false, exportMessage = "无法打开文件保存位置") }
    }

    fun completeExport(context: Context, uri: Uri?) {
        val text = synchronized(exportLock) { pendingExport.also { pendingExport = null } }
        if (uri == null || text == null) {
            mutableState.update { it.copy(exportPending = false) }
            return
        }
        val application = context.applicationContext
        scope.launch {
            val message = try {
                val output = application.contentResolver.openOutputStream(uri, "wt")
                    ?: error("无法写入所选文件")
                output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                "报告已导出"
            } catch (error: Exception) {
                "导出失败：${error.message ?: error.javaClass.simpleName}".take(256)
            }
            mutableState.update { it.copy(exportPending = false, exportMessage = message) }
        }
    }

    private fun vpnSnapshot(): Pair<String, String> {
        val vpn = when {
            RRVpnService.isStarting.value -> "STARTING"
            RRVpnService.isRunning.value -> "CONNECTED"
            else -> "DISCONNECTED"
        }
        val measurement = RRVpnService.engineRestartMeasurement.value
        val activeEngine = when {
            vpn == "DISCONNECTED" -> "NONE"
            vpn == "CONNECTED" && measurement.success -> measurement.engine.ifBlank { "UNKNOWN" }
            else -> "UNKNOWN"
        }
        return vpn to activeEngine
    }

    private fun networkSnapshot(context: Context): String = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching "UNKNOWN"
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?: return@runCatching "NONE_OR_UNKNOWN"
        val transports = buildList {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
        }.joinToString("+").ifBlank { "OTHER" }
        "$transports; android_validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
    }.getOrDefault("UNKNOWN")
}
