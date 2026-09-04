package com.rr.client.lab

import android.content.Context
import android.os.Build
import com.rr.client.BuildConfig
import com.rr.client.RRApplication
import com.rr.client.core.model.ProtocolType
import com.rr.client.subscription.ProtocolCapabilityRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object StartupSelfCheck {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _report = MutableStateFlow<SelfCheckReport?>(null)
    val report: StateFlow<SelfCheckReport?> = _report.asStateFlow()

    fun schedule(context: Context) {
        scope.launch {
            _report.value = run(context.applicationContext)
        }
    }

    suspend fun run(context: Context): SelfCheckReport = withContext(Dispatchers.IO) {
        val checks = mutableListOf<LabCheck>()

        checks += LabCheck(
            name = "RRBOX 版本",
            status = LabCheckStatus.INFO,
            detail = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        )

        val libboxReady = runCatching { Class.forName("io.nekohasekai.libbox.Libbox") }.isSuccess
        checks += LabCheck(
            name = "sing-box libbox",
            status = if (libboxReady) LabCheckStatus.PASS else LabCheckStatus.FAIL,
            detail = if (libboxReady) "libbox Java binding 已加载" else "未找到 libbox Java binding"
        )

        val nativeDir = context.applicationInfo.nativeLibraryDir.orEmpty()
        val hevFile = File(nativeDir, "libhev-socks5-tunnel.so")
        checks += LabCheck(
            name = "HEV native",
            status = if (hevFile.isFile && hevFile.length() > 0L) LabCheckStatus.PASS else LabCheckStatus.FAIL,
            detail = if (hevFile.isFile) "${hevFile.length()} bytes" else "未找到 arm64 HEV native library"
        )

        val rules = listOf(
            "rules/geosite-geolocation-cn.srs",
            "rules/geoip-cn.srs"
        )
        rules.forEach { path ->
            val size = runCatching { context.assets.open(path).use { it.available() } }.getOrNull()
            checks += LabCheck(
                name = "SRS ${path.substringAfterLast('/')}",
                status = if (size != null && size > 8) LabCheckStatus.PASS else LabCheckStatus.FAIL,
                detail = size?.let { "$it bytes" } ?: "资源不可读取"
            )
        }

        val dbReady = runCatching {
            RRApplication.instance.database.trafficDao().getRecentTraffic()
        }.isSuccess
        checks += LabCheck(
            name = "本地数据库",
            status = if (dbReady) LabCheckStatus.PASS else LabCheckStatus.FAIL,
            detail = if (dbReady) "Room 数据库可读" else "数据库读取失败"
        )

        val protocolTypes = ProtocolType.values().toList()
        val capabilityFailures = protocolTypes.filter { type ->
            runCatching { ProtocolCapabilityRegistry.capability(type) }.isFailure
        }
        checks += LabCheck(
            name = "协议能力矩阵",
            status = if (capabilityFailures.isEmpty()) LabCheckStatus.PASS else LabCheckStatus.FAIL,
            detail = if (capabilityFailures.isEmpty()) {
                "${protocolTypes.size}/${protocolTypes.size} 类型已声明分享链接/Raw JSON 兼容边界"
            } else {
                "缺少：${capabilityFailures.joinToString()}"
            }
        )

        val arm64 = Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }
        checks += LabCheck(
            name = "CPU 架构",
            status = if (arm64) LabCheckStatus.PASS else LabCheckStatus.FAIL,
            detail = Build.SUPPORTED_ABIS.joinToString()
        )

        SelfCheckReport(checks = checks).also { report ->
            RRLogStore.record(
                "SELF_CHECK",
                "启动自检完成: ${report.checks.count { it.status == LabCheckStatus.PASS }} PASS, " +
                    "${report.checks.count { it.status == LabCheckStatus.FAIL }} FAIL"
            )
        }
    }
}
