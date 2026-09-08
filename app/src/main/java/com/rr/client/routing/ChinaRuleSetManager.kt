package com.rr.client.routing

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import com.google.gson.JsonParser
import com.rr.client.BuildConfig
import com.rr.client.vpn.VpnRuntimeStateStore
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One immutable generation supplies both DNS and routing, for every forwarding engine. */
object ChinaRuleSetManager {
    data class Paths(
        val geositeChina: String,
        val geoipChina: String,
        val policy: RoutingPolicySnapshot = RoutingPolicySnapshot.bundled(),
        val generation: String = "",
        val bundleVersion: Long = 0L,
        val updatedAtMillis: Long = 0L
    )

    data class UpdateResult(
        val updatedAtMillis: Long,
        val totalBytes: Long,
        val generation: String,
        val changed: Boolean,
        val baseGeneration: String,
        val paths: Paths,
        val operation: Long
    )

    data class Status(
        val policyVersion: Long = RoutingPolicySnapshot.bundled().ruleVersion,
        val bundleVersion: Long = 0L,
        val description: String = "使用内置分流规则",
        val message: String = "",
        val busy: Boolean = false,
        val savedAtMillis: Long = 0L,
        val candidateGeneration: String? = null,
        val operation: Long = 0L
    )

    private val state = MutableStateFlow(Status())
    val status = state.asStateFlow()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updateMutex = Mutex()
    private val operationSequence = AtomicLong()
    private val stores = mutableMapOf<String, RuleSetGenerationStore>()
    private val payloadNames = listOf("geosite-geolocation-cn.srs", "geoip-cn.srs", "rrbox-policy.json")
    private val fileNames = payloadNames + "bundle-manifest.json"
    private const val BUNDLED_MARKER = "{\"bundled\":true}"
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(50, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(false).build()
    }

    fun ensureBundled(context: Context): Result<Paths> = runCatching {
        val store = store(context)
        store.current()?.let { return@runCatching it.paths().also(::showCurrent) }
        // Migrate the last complete China pair, keeping old runtime paths intact.
        val legacyDirectory = File(context.filesDir, "rules")
        val legacy = runCatching {
            RuleSetGenerationStore(legacyDirectory, payloadNames.take(2),
                { SrsRuleSetValidation.validate(it, Libbox::checkConfig) }, directorySync = ::syncDirectory)
                .current()?.files
        }.getOrNull() ?: payloadNames.take(2).map { File(legacyDirectory, it) }.takeIf { it.all(File::isFile) }
        fun install(useLegacy: Boolean) = store.install(onlyIfMissing = true) { targets ->
            payloadNames.forEachIndexed { index, name ->
                val input = if (useLegacy && index < 2) legacy!![index].inputStream()
                    else context.assets.open("rules/$name")
                input.use { source -> targets[index].outputStream().use { SrsRuleSetValidation.copyBounded(source, it) } }
            }
            targets.last().writeText(BUNDLED_MARKER)
        }
        val installed = if (legacy != null) runCatching { install(true) }.getOrElse { install(false) }
            else install(false)
        installed.snapshot.paths().also(::showCurrent)
    }

    fun currentPaths(context: Context): Paths? = runCatching { store(context).current()?.paths() }.getOrNull()

    fun preparedPaths(context: Context, generation: String): Paths? =
        runCatching { store(context).snapshot(generation)?.paths() }.getOrNull()

    /** Download and validate only. The active pointer is unchanged until the caller/engine commits. */
    suspend fun update(context: Context): Result<UpdateResult> {
        var prepared: String? = null
        var operation = -1L
        try {
            return withContext(Dispatchers.IO) { updateMutex.withLock {
            val coroutineContext = currentCoroutineContext()
            operation = operationSequence.incrementAndGet()
            state.value = state.value.copy(busy = true, message = "正在获取已验证的规则版本…", candidateGeneration = null, operation = operation)
            runCatching {
                val base = ensureBundled(context).getOrThrow()
                val channel = firstAvailable(
                    listOf(
                        "https://raw.githubusercontent.com/Xiaowu7z/RR-BOX/rules-channel/channel.json",
                        "https://testingcf.jsdelivr.net/gh/Xiaowu7z/RR-BOX@rules-channel/channel.json"
                    ), 4096L, { coroutineContext.ensureActive() }, RuleBundleManifest::parseChannel
                )
                require(channel >= base.bundleVersion) { "更新源版本落后于当前规则，请稍后重试" }
                val signed = firstAvailable(RuleBundleManifest.assetUrls(channel, "bundle-manifest.json"),
                    RuleBundleManifest.MAX_MANIFEST_BYTES.toLong(), { coroutineContext.ensureActive() }) { bytes ->
                    bytes to RuleBundleManifest.verify(bytes, channel, BuildConfig.VERSION_CODE)
                }
                val (envelope, manifest) = signed
                require(manifest.policyVersion >= base.policy.ruleVersion) { "拒绝旧版自定义规则" }
                if (channel == base.bundleVersion) {
                    val current = store(context).snapshot(base.generation) ?: error("当前规则已变化，请重试")
                    require(current.files.last().readBytes().contentEquals(envelope)) { "同一规则包的发布内容发生变化，拒绝替换" }
                    state.value = state.value.copy(busy = true, candidateGeneration = base.generation, message = "规则已是最新")
                    return@runCatching UpdateResult(base.updatedAtMillis, current.files.sumOf { it.length() },
                        base.generation, false, base.generation, base, operation)
                }
                val result = store(context).prepare { targets ->
                    payloadNames.forEachIndexed { index, name ->
                        coroutineContext.ensureActive()
                        val label = listOf("中国域名规则", "中国 IP 规则", "RRBOX 自定义规则")[index]
                        state.value = state.value.copy(message = "正在下载并校验$label…")
                        val asset = manifest.assets.getValue(name)
                        val bytes = firstAvailable(RuleBundleManifest.assetUrls(channel, name), asset.size,
                            { coroutineContext.ensureActive() }) { body -> body.also(asset::verify) }
                        targets[index].writeBytes(bytes)
                    }
                    val policy = RoutingPolicySnapshot.parse(targets[2].readBytes())
                    require(policy.ruleVersion == manifest.policyVersion) { "规则版本与签名清单不一致" }
                    if (policy.ruleVersion == base.policy.ruleVersion) {
                        // A version identifies content. JSON formatting changes may not hide a policy change.
                        require(policy == base.policy) { "自定义规则内容已变化但版本未递增" }
                    }
                    targets.last().writeBytes(envelope)
                    coroutineContext.ensureActive()
                }
                prepared = result.snapshot.generation
                val paths = result.snapshot.paths()
                state.value = state.value.copy(
                    message = if (result.changed) "下载及校验完成，等待启用…" else "规则已是最新",
                    busy = true, candidateGeneration = result.snapshot.generation
                )
                UpdateResult(result.snapshot.updatedAtMillis, result.snapshot.files.sumOf { it.length() },
                    result.snapshot.generation, result.changed, base.generation, paths, operation)
            }.onFailure {
                state.value = state.value.copy(busy = false, message = "更新未完成，继续使用原有规则：${it.message.orEmpty().take(160)}")
                if (it is CancellationException) throw it
            }
            } }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                prepared?.let { runCatching { store(context).discardPrepared(it) } }
                state.update { current ->
                    if (current.operation == operation) current.copy(
                        busy = false, candidateGeneration = null, message = "规则更新已取消，继续使用原有规则"
                    ) else current
                }
            }
            throw cancelled
        }
    }

    /** Local compare-and-swap; called after actual engine activation, or while disconnected. */
    fun commitPrepared(context: Context, candidateGeneration: String, expectedBase: String, expectedOperation: Long): Boolean {
        if (!RuleUpdateIdentity.owns(state.value.operation,
                state.value.candidateGeneration, expectedOperation, candidateGeneration)) return false
        val store = store(context)
        val candidate = store.snapshot(candidateGeneration) ?: return false
        val success = store.activate(candidate, expectedBase)
        if (success) showCurrent(candidate.paths(), "分流规则已启用")
        return success
    }

    fun matchesPreparedConfig(context: Context, generation: String, config: String): Boolean = runCatching {
        val paths = preparedPaths(context, generation) ?: return false
        val actual = JsonParser.parseString(config).asJsonObject.getAsJsonObject("route")
            .getAsJsonArray("rule_set").mapNotNull { it.asJsonObject["path"]?.asString }.toSet()
        actual == setOf(paths.geositeChina, paths.geoipChina)
    }.getOrDefault(false)

    fun noteActivationFailure(context: Context, candidateGeneration: String, reason: String, operation: Long) {
        // Claim ownership before touching storage. Old completion callbacks must neither
        // clear a newer update of the same generation nor wait on its download/write lock.
        while (true) {
            val current = state.value
            if (!RuleUpdateIdentity.owns(current.operation, current.candidateGeneration, operation, candidateGeneration)) return
            if (state.compareAndSet(current, current.copy(busy = false, candidateGeneration = null,
                    message = "新规则未启用：${reason.take(180)}"))) break
        }
        val appContext = context.applicationContext
        cleanupScope.launch { runCatching { store(appContext).discardPrepared(candidateGeneration) } }
    }

    private fun showCurrent(paths: Paths, message: String? = null) {
        state.value = state.value.copy(policyVersion = paths.policy.ruleVersion, bundleVersion = paths.bundleVersion,
            description = paths.policy.description, savedAtMillis = paths.updatedAtMillis, message = message ?: state.value.message,
            busy = if (message != null) false else state.value.busy,
            candidateGeneration = if (message != null) null else state.value.candidateGeneration)
    }

    private fun <T> firstAvailable(urls: List<String>, limit: Long, ensureActive: () -> Unit, decode: (ByteArray) -> T): T {
        var last: Exception? = null
        urls.forEach { url ->
            try { return decode(download(url, limit, ensureActive)) }
            catch (error: Exception) { if (error is CancellationException) throw error; last = error }
        }
        throw IllegalStateException("无法读取规则更新源", last)
    }

    private fun download(url: String, limit: Long, ensureActive: () -> Unit): ByteArray {
        require(limit in 1..SrsRuleSetValidation.MAX_FILE_BYTES)
        ensureActive()
        val request = Request.Builder().url(url).header("User-Agent", "RRBOX signed rule updater").build()
        return client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "规则下载 HTTP ${response.code}" }
            require(response.request.url.isHttps) { "规则更新要求 HTTPS" }
            val body = response.body ?: error("规则下载为空")
            require(body.contentLength() <= limit) { "规则下载超过大小限制" }
            body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= limit) { "规则下载超过大小限制" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }

    private fun validateBundle(files: List<File>) {
        SrsRuleSetValidation.validate(files.take(2), Libbox::checkConfig)
        require(files[2].length() in 1L..RoutingPolicySnapshot.MAX_BYTES.toLong())
        require(files[3].length() in 1L..RuleBundleManifest.MAX_MANIFEST_BYTES.toLong())
        val policy = RoutingPolicySnapshot.parse(files[2].readBytes())
        val envelope = files[3].readBytes()
        if (envelope.toString(Charsets.UTF_8) != BUNDLED_MARKER) {
            // The version in the signed payload is verified before it can affect paths/configuration.
            val outer = JsonParser.parseString(envelope.toString(Charsets.UTF_8)).asJsonObject
            val payload = java.util.Base64.getDecoder().decode(outer["payload"].asString)
            val version = JsonParser.parseString(payload.toString(Charsets.UTF_8)).asJsonObject["bundleVersion"].asLong
            val manifest = RuleBundleManifest.verify(envelope, version, BuildConfig.VERSION_CODE)
            require(manifest.policyVersion == policy.ruleVersion)
            payloadNames.forEachIndexed { index, name ->
                val asset = manifest.assets.getValue(name)
                require(files[index].length() == asset.size && sha256(files[index].readBytes()) == asset.sha256)
            }
        }
    }

    private fun store(context: Context): RuleSetGenerationStore = synchronized(stores) {
        val appContext = context.applicationContext
        val directory = File(appContext.filesDir, "routing-bundles")
        stores.getOrPut(directory.absolutePath) {
            RuleSetGenerationStore(directory, fileNames, ::validateBundle, protectedPaths = {
                val cached = VpnRuntimeStateStore(appContext).load()
                if (cached == null) emptySet() else JsonParser.parseString(cached.configJson).asJsonObject
                    .getAsJsonObject("route")?.getAsJsonArray("rule_set")
                    ?.mapNotNull { it.asJsonObject["path"]?.asString }?.toSet().orEmpty()
            }, directorySync = ::syncDirectory).also { generationStore ->
                // No in-flight transaction survives app process death. Keep cache-referenced
                // paths, but release abandoned preparation markers from a previous process.
                val cachedPaths = runCatching {
                    val cached = VpnRuntimeStateStore(appContext).load()
                    if (cached == null) emptySet() else JsonParser.parseString(cached.configJson).asJsonObject
                        .getAsJsonObject("route")?.getAsJsonArray("rule_set")
                        ?.mapNotNull { it.asJsonObject["path"]?.asString }?.toSet().orEmpty()
                }.getOrNull()
                if (cachedPaths != null) generationStore.preparedGenerations().forEach { id ->
                    val prefix = File(File(directory, "generations"), id).absolutePath + File.separator
                    if (cachedPaths.none { it.startsWith(prefix) }) generationStore.discardPrepared(id)
                }
            }
        }
    }

    private fun RuleSetGenerationStore.Snapshot.paths(): Paths {
        val envelope = files[3].readText()
        val version = if (envelope == BUNDLED_MARKER) 0L else {
            val payload = java.util.Base64.getDecoder().decode(JsonParser.parseString(envelope).asJsonObject["payload"].asString)
            JsonParser.parseString(payload.toString(Charsets.UTF_8)).asJsonObject["bundleVersion"].asLong
        }
        return Paths(files[0].absolutePath, files[1].absolutePath, RoutingPolicySnapshot.parse(files[2].readBytes()),
            generation, version, updatedAtMillis)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun syncDirectory(path: File) {
        require(path.isDirectory)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) OsConstants.O_CLOEXEC else 0
        val descriptor = Os.open(path.absolutePath, OsConstants.O_RDONLY or flags, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }
}
