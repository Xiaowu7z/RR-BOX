package com.rr.client.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.os.Process as AndroidProcess
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import com.rr.client.core.RootConfigAdapter
import com.rr.client.routing.ResolvedPerAppPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * One authenticated, leased root helper and one nonpersistent TUN. The helper configures only
 * its own interface/routes; libbox still owns all packet processing. No VpnService.Builder,
 * firewall script, persistent PID file, or configuration text is passed through su.
 */
class RootVpnEngine(context: Context, private val onUnexpectedExit: (String) -> Unit) {
    private val app = context.applicationContext
    private val stateLock = Any()
    @Volatile private var session: Session? = null
    @Volatile private var cleanupFailure: String? = null
    @Volatile private var pendingCleanup: Session? = null

    val isPrepared: Boolean get() = session?.let { it.prepared && !it.stopping && it.failure == null } == true
    val isRunning: Boolean get() = session?.let { it.active && !it.stopping && it.failure == null } == true
    val interfaceName: String? get() = session?.takeIf { !it.stopping }?.interfaceName?.takeIf { it.isNotEmpty() }

    suspend fun prepare(configJson: String): String = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val runtime = RootConfigAdapter.adapt(configJson)
        val configCommand = configuration(runtime.perAppPolicy)
        val binary = File(app.applicationInfo.nativeLibraryDir, RootEngineProtocol.BINARY_NAME)
        check(binary.isFile && binary.canExecute()) { "APK 中缺少可执行的 Root 引擎" }
        val started = SystemClock.elapsedRealtime()
        val pid = AndroidProcess.myPid()
        val startTicks = RootEngineProtocol.processStartTicks(File("/proc/self/stat").readText(), pid)
        val nonce = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val socketName = "rrbox-root-$nonce"
        val current = Session(started)
        synchronized(stateLock) {
            check(cleanupFailure == null) { cleanupFailure.orEmpty() }
            check(session == null) { "Root 引擎已经准备或运行" }
            session = current
        }
        try {
            val server = RootListener(socketName)
            synchronized(stateLock) {
                if (current.stopping) { server.close(); throw CancellationException("Root startup stopped") }
                current.server = server
            }
            currentCoroutineContext().ensureActive()
            val child = ProcessBuilder(RootEngineProtocol.arguments(binary.absolutePath, socketName,
                AndroidProcess.myUid(), pid, startTicks, started)).start()
            current.process = child
            drain(child.inputStream, current.stdout, "stdout")
            drain(child.errorStream, current.stderr, "stderr")
            daemon("stdin-close") { runCatching { child.outputStream.close() } }
            if (current.stopping) {
                daemon("cancelled-launch") {
                    runCatching { child.destroy() }
                    runCatching { if (child.isAlive) child.destroyForcibly() }
                }
                throw CancellationException("Root startup stopped")
            }
            val peer = acceptRootPeer(current, server)
            synchronized(stateLock) {
                if (current.stopping) { peer.close(); throw CancellationException("Root startup stopped") }
                current.socket = peer
            }
            server.close()
            current.server = null
            val deadline = minOf(started + RootEngineProtocol.STARTUP_MILLIS, SystemClock.elapsedRealtime() + CONTROL_MILLIS)
            setTimeout(peer, deadline)
            val marker = peer.inputStream.read()
            val descriptors = peer.ancillaryFileDescriptors.orEmpty()
            try {
                if (marker != RootEngineProtocol.FD_MARKER.code) {
                    val response = if (marker in 32..126) {
                        runCatching { marker.toChar().toString() + readLine(peer, deadline) }
                            .getOrDefault(marker.toChar().toString())
                    } else "控制通道已关闭"
                    throw IOException("Root TUN 初始化失败：$response")
                }
                check(descriptors.size == 1) {
                    "Root 引擎没有传回唯一的 TUN 文件描述符"
                }
                val retained = ParcelFileDescriptor.dup(descriptors.single())
                synchronized(stateLock) {
                    if (current.stopping) { retained.close(); throw CancellationException("Root startup stopped") }
                    current.tun = retained
                }
            } finally {
                descriptors.forEach { descriptor -> runCatching { Os.close(descriptor) } }
            }
            val ready = readLine(peer, deadline)
            check(ready.matches(Regex("READY rr[0-9a-f]{12}"))) { "Root TUN 握手无效：$ready" }
            check(ready.substringAfter(' ') == "rr${nonce.take(12)}") { "Root TUN 会话标识不匹配" }
            current.interfaceName = ready.substringAfter(' ')
            expect(current, configCommand, "CONFIGURED", startupCommandTimeout(current))
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) {
                check(!current.stopping) { "Root startup stopped" }
                current.prepared = true
            }
            startMonitoring(current)
            Log.i(TAG, "Root TUN 已准备：${current.interfaceName}；等待 sing-box 接收 fd")
            runtime.configJson
        } catch (error: Exception) {
            withContext(NonCancellable) { runCatching { stopSession(current) }.onFailure { error.addSuppressed(it) } }
            if (error is CancellationException) throw error
            throw IOException("Root 引擎准备失败：${error.message.orEmpty()}${current.diagnostics()}", error)
        }
    }

    /** Ownership moves exactly once to BoxServiceWrapper, which closes it after stop(). */
    fun takeTunFd(): Int = synchronized(stateLock) {
        val current = session ?: error("Root 引擎尚未准备")
        check(current.prepared && !current.stopping && current.failure == null) { "Root TUN 不可用" }
        val descriptor = current.tun ?: error("Root TUN 文件描述符已移交")
        descriptor.detachFd().also { current.tun = null; current.fdTransferred = true }
    }

    suspend fun activate(): Unit = withContext(Dispatchers.IO) {
        val current = session ?: error("Root 引擎尚未准备")
        check(current.prepared && current.fdTransferred && !current.stopping && !current.active) { "Root TUN 尚未准备或已经激活" }
        try {
            currentCoroutineContext().ensureActive()
            // Set before sending: a lost ACTIVE acknowledgement must be treated as possible
            // route installation, and must not permit a switch before cleanup is verified.
            current.activationAttempted = true
            expect(current, "ACTIVATE", "ACTIVE", startupCommandTimeout(current))
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) {
                check(!current.stopping && current.failure == null) { "Root 引擎已停止" }
                current.active = true
            }
            Log.i(TAG, "Root TUN 已接管流量：${current.interfaceName}")
        } catch (error: Exception) {
            val failure = if (error !is CancellationException &&
                Regex("stage=install_ipv[46]_dns_port_rule(?:\\s|$)").containsMatchIn(error.message.orEmpty())) {
                IOException("Root DNS 端口规则安装失败，系统可能不支持按端口分流；" +
                    "请使用稳定或 HEV 模式。${error.message.orEmpty()}", error)
            } else error
            failSession(current, "Root 激活失败：${failure.message.orEmpty()}")
            throw failure
        }
    }

    /** Short synchronous IPC; saturation returns UNKNOWN without queuing per-flow threads. */
    fun findConnectionOwner(protocol: Int, sourceAddress: String, sourcePort: Int,
                            destinationAddress: String, destinationPort: Int): Int {
        val current = session ?: return -1
        if (!current.prepared || current.stopping || current.failure != null) return -1
        val command = RootEngineProtocol.ownerCommand(protocol, sourceAddress, sourcePort,
            destinationAddress, destinationPort) ?: return -1
        try {
            if (!current.io.tryLock(OWNER_LOCK_MILLIS, TimeUnit.MILLISECONDS)) return -1
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return -1
        }
        return try {
            if (current.stopping || current.failure != null) -1
            else RootEngineProtocol.ownerUid(exchangeLocked(current, command, OWNER_MILLIS))
        } catch (error: Exception) {
            // A timed-out stream cannot be reused: a late UID answer could belong to the next flow.
            failSession(current, "Root UID 查询通道失效：${error.message.orEmpty()}")
            -1
        } finally { current.io.unlock() }
    }

    suspend fun stop(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        session?.let { stopSession(it) }
        val recovered = synchronized(stateLock) {
            pendingCleanup?.takeIf { it.stdout.cleanup == true }?.also {
                pendingCleanup = null
                cleanupFailure = null
            }
        }
        recovered?.let {
            releaseProcess(it)
            Log.i(TAG, "Root 延迟路由清理已经确认")
        }
        cleanupFailure?.let { throw IOException(it) }
    }

    private suspend fun acceptRootPeer(current: Session, server: RootListener): LocalSocket {
        val deadline = current.started + RootEngineProtocol.GRANT_MILLIS
        val poll = StructPollfd().apply { fd = server.fileDescriptor; events = OsConstants.POLLIN.toShort() }
        while (SystemClock.elapsedRealtime() < deadline) {
            currentCoroutineContext().ensureActive()
            check(!current.stopping) { "Root 授权已取消" }
            current.process?.let { child ->
                runCatching { child.exitValue() }.getOrNull()?.let { code ->
                    throw IOException("su 已退出，状态 $code${current.diagnostics()}")
                }
            }
            poll.revents = 0
            if (Os.poll(arrayOf(poll), ACCEPT_POLL_MILLIS) <= 0) continue
            if (poll.revents.toInt() and OsConstants.POLLIN == 0) throw IOException("Root 监听套接字已关闭")
            val peer = try { server.accept() } catch (error: IOException) {
                if (SystemClock.elapsedRealtime() >= deadline || current.stopping) throw error
                continue
            }
            val credentials = runCatching { peer.peerCredentials }.getOrNull()
            if (credentials?.uid == 0 && credentials.pid > 0) return peer
            // A different local app cannot impersonate root, even if it finds the abstract name.
            runCatching { peer.close() }
        }
        throw SocketTimeoutException("Root 授权等待超时；稍后的授权不会接管流量")
    }

    private fun configuration(policy: ResolvedPerAppPolicy): String {
        fun resolve(packages: List<String>): List<Int> = packages.map { name ->
            try {
                @Suppress("DEPRECATION")
                val info = app.packageManager.getApplicationInfo(name, 0)
                info.uid
            } catch (error: Exception) {
                throw IllegalArgumentException("Root 分应用策略无法读取已安装应用 $name 的 UID，请检查应用列表", error)
            }
        }
        val include = resolve(policy.allowedPackages)
        val exclude = resolve(policy.disallowedPackages)
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val physical = connectivity.allNetworks.filter { network ->
            connectivity.getNetworkCapabilities(network)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } == true
        }
        val selected = connectivity.activeNetwork?.takeIf { it in physical }?.let(::listOf) ?: physical
        val dns = selected.flatMap { connectivity.getLinkProperties(it)?.dnsServers.orEmpty() }
            .mapNotNull { it.hostAddress }.distinct()
            .ifEmpty { listOf("223.5.5.5", "119.29.29.29", "114.114.114.114") }
        return RootEngineProtocol.configure(include, exclude, dns, AndroidProcess.myUid())
    }

    private fun startMonitoring(current: Session) {
        current.scope.launch {
            while (!current.stopping && current.failure == null) {
                delay(HEARTBEAT_MILLIS)
                if (current.stopping) break
                // An in-flight ACTIVATE already holds the helper's attention and renews its
                // lease. Contention is not a broken channel; never kill a healthy activation.
                if (!current.io.tryLock(OWNER_LOCK_MILLIS, TimeUnit.MILLISECONDS)) continue
                try {
                    if (current.stopping || current.failure != null) break
                    check(exchangeLocked(current, "HEARTBEAT", HEARTBEAT_TIMEOUT_MILLIS) == "OK") {
                        "Root 心跳响应无效"
                    }
                }
                catch (error: Exception) {
                    if (error !is CancellationException && !current.stopping) {
                        failSession(current, "Root 心跳中断：${error.message.orEmpty()}")
                    }
                    break
                } finally { current.io.unlock() }
            }
        }
        current.scope.launch {
            while (!current.stopping && current.failure == null) {
                val code = current.process?.let { runCatching { it.exitValue() }.getOrNull() }
                if (code != null) {
                    failSession(current, "Root 引擎意外退出，状态 $code${current.diagnostics()}")
                    break
                }
                delay(250)
            }
        }
    }

    private fun expect(current: Session, command: String, expected: String, timeout: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        if (!current.io.tryLock(timeout, TimeUnit.MILLISECONDS)) throw SocketTimeoutException("Root 控制通道忙碌超时")
        try {
            check(!current.stopping && current.failure == null) { "Root 控制通道已停止" }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) throw SocketTimeoutException("Root 控制操作超时")
            val response = exchangeLocked(current, command, remaining)
            check(response == expected) { "Root 引擎返回 $response，预期 $expected" }
        } finally { current.io.unlock() }
    }

    private fun exchangeLocked(current: Session, command: String, timeout: Long): String {
        val peer = current.socket ?: throw IOException("Root 控制通道已关闭")
        require(command.length + 1 <= RootEngineProtocol.MAX_COMMAND_BYTES && '\n' !in command && '\r' !in command)
        val deadline = SystemClock.elapsedRealtime() + timeout
        setTimeout(peer, deadline)
        peer.outputStream.write((command + "\n").toByteArray(Charsets.US_ASCII))
        return readLine(peer, deadline).also {
            if (it.startsWith("ERROR ")) throw IOException(it)
        }
    }

    private fun readLine(peer: LocalSocket, deadline: Long): String {
        val result = StringBuilder()
        // Native ERROR responses carry a bounded phase/argv/exit diagnostic.
        // Keep the absolute I/O deadline and an explicit size cap for every reply.
        while (result.length < 1536) {
            setTimeout(peer, deadline)
            val value = peer.inputStream.read()
            if (value < 0) throw IOException("Root 控制通道意外关闭")
            if (value == '\n'.code) return result.toString()
            if (value !in 32..126) throw IOException("Root 控制响应包含无效字符")
            result.append(value.toChar())
        }
        throw IOException("Root 控制响应过长")
    }

    private fun setTimeout(peer: LocalSocket, deadline: Long) {
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0) throw SocketTimeoutException("Root 控制操作超时")
        // LocalSocket sets both SO_RCVTIMEO and SO_SNDTIMEO. Each byte shares one absolute
        // elapsed-time deadline, so a peer sending partial replies cannot extend a call.
        peer.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun startupCommandTimeout(current: Session): Long {
        val remaining = current.started + RootEngineProtocol.STARTUP_MILLIS - SystemClock.elapsedRealtime()
        if (remaining <= 0) throw SocketTimeoutException("Root 启动期限已过，请重新连接")
        return minOf(CONTROL_MILLIS, remaining)
    }

    private fun failSession(current: Session, reason: String) {
        val notify = synchronized(stateLock) {
            if (current.stopping || current.failure != null || session !== current) false else {
                current.failure = reason.take(2048)
                current.prepared = false
                current.active = false
                true
            }
        }
        if (!notify) return
        closeChannel(current)
        Log.e(TAG, reason)
        runCatching { onUnexpectedExit(reason) }.onFailure { Log.w(TAG, "Root failure callback failed", it) }
    }

    private suspend fun stopSession(current: Session) = current.stopMutex.withLock {
        if (current.finished) return@withLock
        synchronized(stateLock) { current.stopping = true; current.prepared = false; current.active = false }
        current.scope.cancel()
        current.server?.let { server -> daemon("listener-close") { runCatching { server.close() } } }
        current.server = null
        var verified = current.stdout.cleanup == true
        var failure: Exception? = null
        val deadline = SystemClock.elapsedRealtime() + CONTROL_MILLIS
        if (current.socket != null && current.failure == null) {
            if (current.io.tryLock(STOP_LOCK_MILLIS, TimeUnit.MILLISECONDS)) {
                try {
                    verified = exchangeLocked(current, "STOP", (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)) == "STOPPED"
                } catch (error: Exception) { failure = error }
                finally { current.io.unlock() }
            } else { failure = SocketTimeoutException("Root 控制命令未结束") }
        }
        closeChannel(current) // EOF asks the native helper to roll back, including partial ACTIVATE.
        synchronized(stateLock) {
            runCatching { current.tun?.close() }
            current.tun = null
        }
        // Pipe draining is independent of blocking Process streams and continues after the
        // byte capture limit. Only the helper's exact post-rollback marker is trusted.
        while (!verified && current.activationAttempted && SystemClock.elapsedRealtime() < deadline) {
            verified = current.stdout.cleanup == true
            if (verified || current.stdout.cleanup == false || current.stdout.finished) break
            delay(25)
        }
        // EOF may race the preceding cleanup read. The finished flag publishes all drained
        // bytes, so re-read the exact marker before deciding the rollback is unconfirmed.
        verified = verified || current.stdout.cleanup == true
        if (!verified && current.activationAttempted) {
            cleanupFailure = "Root 路由清理未获确认，已阻止切换引擎：${failure?.message ?: current.failure ?: "缺少清理回执"}${current.diagnostics()}"
            pendingCleanup = current
        }
        current.finished = true
        synchronized(stateLock) { if (session === current) session = null }
        // Never forcibly kill a helper while it might own routes. Native EOF/parent identity/
        // lease watchdogs perform rollback first. Stream close may block behind read on some
        // root managers, so it must never prolong stop() or cancellation.
        // If cleanup is still pending, leave diagnostic pipes draining. A later stop retry
        // can recover from the exact late receipt; closing them now would lose that evidence.
        if (verified || !current.activationAttempted) releaseProcess(current)
        cleanupFailure?.let { throw IOException(it, failure) }
    }

    private fun releaseProcess(current: Session) {
        current.process?.let { child -> daemon("process-close") {
            runCatching { child.destroy() }
            runCatching { if (child.isAlive) child.destroyForcibly() }
            runCatching { child.outputStream.close() }
            runCatching { child.inputStream.close() }
            runCatching { child.errorStream.close() }
        } }
    }

    private fun closeChannel(current: Session) {
        val socket = synchronized(stateLock) { current.socket.also { current.socket = null } } ?: return
        daemon("socket-close") {
            runCatching { socket.shutdownInput() }
            runCatching { socket.shutdownOutput() }
            runCatching { socket.close() }
        }
    }

    private fun drain(stream: InputStream, capture: Capture, name: String) = daemon(name) {
        try {
            stream.use {
                val buffer = ByteArray(4096)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    capture.append(buffer, count)
                }
            }
        } catch (_: Exception) { /* process-close may interrupt this bounded diagnostic drain */ }
        finally { capture.finished = true }
    }

    private fun daemon(name: String, action: () -> Unit) {
        Thread(action, "rrbox-root-$name").apply { isDaemon = true; start() }
    }

    /**
     * Public API 1 socket options bound accept even if readiness disappears after poll.
     * LocalServerSocket(FileDescriptor) borrows its fd, so retain and close its LocalSocket
     * owner explicitly. This also avoids the API 30-only Os.fcntlInt entry point.
     */
    private class RootListener(name: String) : Closeable {
        private val owner = LocalSocket()
        private val server: LocalServerSocket

        init {
            try {
                owner.bind(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
                // LocalSocket maps SO_TIMEOUT to SO_RCVTIMEO/SO_SNDTIMEO; Linux applies
                // SO_RCVTIMEO to accept(), including on this borrowed listening fd.
                owner.soTimeout = ACCEPT_POLL_MILLIS
                server = LocalServerSocket(owner.fileDescriptor)
            } catch (error: Exception) {
                runCatching { owner.close() }
                throw error
            }
        }

        val fileDescriptor: FileDescriptor get() = server.fileDescriptor
        fun accept(): LocalSocket = server.accept()
        override fun close() {
            try { server.close() } finally { owner.close() }
        }
    }

    private class Session(val started: Long) {
        val io = ReentrantLock(true)
        val stopMutex = Mutex()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val stdout = Capture()
        val stderr = Capture()
        @Volatile var process: Process? = null
        @Volatile var server: RootListener? = null
        @Volatile var socket: LocalSocket? = null
        @Volatile var tun: ParcelFileDescriptor? = null
        @Volatile var prepared = false
        @Volatile var active = false
        @Volatile var fdTransferred = false
        @Volatile var activationAttempted = false
        @Volatile var stopping = false
        @Volatile var finished = false
        @Volatile var failure: String? = null
        var interfaceName = ""
        fun diagnostics() = listOf(stdout.snapshot(), stderr.snapshot()).filter { it.isNotBlank() }
            .joinToString("；").take(2048).let { if (it.isBlank()) "" else "；helper=$it" }
    }

    /** Continue draining and recognizing cleanup after diagnostics reach their size cap. */
    private class Capture {
        private val bytes = ByteArrayOutputStream()
        private val line = StringBuilder()
        @Volatile var cleanup: Boolean? = null
        @Volatile var finished = false
        @Synchronized fun append(buffer: ByteArray, count: Int) {
            bytes.write(buffer, 0, minOf(count, 8192 - bytes.size()))
            for (index in 0 until count) {
                val byte = buffer[index].toInt() and 0xff
                if (byte == '\n'.code) {
                    when (line.toString()) {
                        "RRBOX_ROOT_CLEANUP=OK" -> cleanup = true
                        "RRBOX_ROOT_CLEANUP=FAILED" -> cleanup = false
                    }
                    line.setLength(0)
                } else if (line.length < 256) line.append(byte.toChar())
            }
        }
        @Synchronized fun snapshot(): String = bytes.toString("UTF-8").trim().take(1024)
    }

    private companion object {
        const val TAG = "RootVpnEngine"
        const val ACCEPT_POLL_MILLIS = 100
        const val CONTROL_MILLIS = 15_000L
        const val HEARTBEAT_MILLIS = 3_000L
        const val HEARTBEAT_TIMEOUT_MILLIS = 2_000L
        const val OWNER_MILLIS = 750L
        const val OWNER_LOCK_MILLIS = 50L
        const val STOP_LOCK_MILLIS = 250L
    }
}
