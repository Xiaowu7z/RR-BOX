package com.rr.client.lab

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.rr.client.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Small engine-state breadcrumb survives a native crash without a logcat reader or signal handler.
 * See https://developer.android.com/reference/android/app/ActivityManager#setProcessStateSummary(byte[])
 * No node settings, process memory or tombstone contents are captured.
 */
object ProcessExitDiagnostics {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val breadcrumbPattern = Regex("rrbox-v1;version=[0-9]{1,9};engine=(SYSTEM|HEV|ROOT);phase=(STARTING|RUNNING|FAILED|STOPPED);generation=[0-9]{1,19}")

    fun mark(context: Context, engine: String, phase: String, generation: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val state = "rrbox-v1;version=${BuildConfig.VERSION_CODE};engine=$engine;phase=$phase;generation=$generation"
        if (!breadcrumbPattern.matches(state)) return
        runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.setProcessStateSummary(state.toByteArray(Charsets.US_ASCII))
        }
    }

    /** Query only this app's exits once per process, off the startup/UI thread. */
    fun schedule(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val app = context.applicationContext
        scope.launch {
            try {
                val manager = app.getSystemService(ActivityManager::class.java) ?: return@launch
                val prefs = app.getSharedPreferences("engine-exit-diagnostics", Context.MODE_PRIVATE)
                val after = prefs.getLong("last-exit-timestamp", 0L)
                val exits = manager.getHistoricalProcessExitReasons(app.packageName, 0, 8)
                    .filter { it.processName == app.packageName && it.timestamp > after }
                    .sortedBy { it.timestamp }
                if (exits.isEmpty()) return@launch
                exits.forEach { exit ->
                    val reason = when (exit.reason) {
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生崩溃"
                        ApplicationExitInfo.REASON_CRASH -> "Java 崩溃"
                        ApplicationExitInfo.REASON_ANR -> "应用无响应"
                        ApplicationExitInfo.REASON_SIGNALED -> "系统信号终止"
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "系统内存回收"
                        ApplicationExitInfo.REASON_USER_REQUESTED -> "用户或系统请求停止"
                        ApplicationExitInfo.REASON_EXIT_SELF -> "进程自行退出"
                        else -> "其他系统退出原因"
                    }
                    val state = exit.processStateSummary?.takeIf { it.size <= 128 }
                        ?.toString(Charsets.US_ASCII)?.takeIf(breadcrumbPattern::matches)
                        ?: "旧进程未提供运行阶段"
                    RRLogStore.record("CRASH", "系统退出记录补录；退出时间(ms)=${exit.timestamp}；" +
                        "PID=${exit.pid}；原因=$reason(${exit.reason})；状态或信号=${exit.status}；$state；" +
                        "这是历史进程退出信息，不表示当前连接失败")
                }
                // Flush accepted records before advancing the cursor; storage errors retry next launch.
                RRLogStore.query(limit = 1)
                if (RRLogStore.state.value.error == null) {
                    prefs.edit().putLong("last-exit-timestamp", exits.last().timestamp).commit()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                RRLogStore.record("CRASH", "系统退出记录读取失败：${error.javaClass.simpleName}")
            }
        }
    }
}
