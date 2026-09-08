package com.rr.client.routing

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** Immutable generations + one atomic pointer: readers can never observe half an update. */
internal class RuleSetGenerationStore(
    private val directory: File,
    private val fileNames: List<String>,
    private val validate: (List<File>) -> Unit,
    private val protectedPaths: () -> Set<String> = { emptySet() },
    private val atomicMove: (File, File) -> Unit = ::moveAtomically,
    private val maxStoredBytes: Long = 128L * 1024 * 1024,
    private val directorySync: (File) -> Unit = ::syncDirectory
) {
    data class Snapshot(val generation: String, val files: List<File>, val updatedAtMillis: Long)
    data class Installation(val snapshot: Snapshot, val changed: Boolean)

    private data class Pointer(val current: String, val previous: String?, val updatedAtMillis: Long)
    private data class Verified(val stamp: List<Pair<Long, Long>>, val hashes: List<String>)

    private val readLock = Any()
    private val writeLock = Any()
    private val generations = File(directory, "generations")
    private val activeFile = File(directory, "active.properties")
    private val backupFile = File(directory, "active-backup.properties")
    private val verified = mutableMapOf<String, Verified>()
    // A config may be compiled now and started later, or still be running while its cache changes.
    // Do not delete any path issued in this process. Persisted runtime paths survive process death.
    private val issued = mutableSetOf<String>()

    fun current(): Snapshot? = synchronized(readLock) { currentLocked() }

    fun install(
        updatedAtMillis: Long = System.currentTimeMillis(),
        onlyIfMissing: Boolean = false,
        write: (List<File>) -> Unit
    ): Installation = synchronized(writeLock) {
        val previous = current()
        val installation = prepare(updatedAtMillis, onlyIfMissing, write)
        if (installation.changed) {
            check(activate(installation.snapshot, previous?.generation)) { "规则集已被其他更新替换，请重试" }
        } else if (!onlyIfMissing) {
            // Legacy callers use this timestamp as the successful refresh time. Transactional
            // updates use prepare(), whose timestamp always describes the installed content.
            synchronized(readLock) {
                val old = installation.snapshot
                val pointer = readPointer(activeFile)?.takeIf { it.current == old.generation }
                    ?: Pointer(old.generation, null, old.updatedAtMillis)
                writePointer(activeFile, pointer.copy(updatedAtMillis = updatedAtMillis))
            }
            return@synchronized Installation(installation.snapshot.copy(updatedAtMillis = updatedAtMillis), false)
        }
        installation
    }

    /** Download and validate a candidate without changing what current() returns. */
    fun prepare(
        updatedAtMillis: Long = System.currentTimeMillis(),
        onlyIfMissing: Boolean = false,
        write: (List<File>) -> Unit
    ): Installation = synchronized(writeLock) {
        if (onlyIfMissing) {
            current()?.let { return@synchronized Installation(it, false) }
        }
        check(generations.mkdirs() || generations.isDirectory) { "无法创建规则集目录" }
        // Staging directories were never published and cannot be referenced by a runtime.
        generations.listFiles { f -> f.name.startsWith(".stage-") }?.forEach { it.deleteRecursively() }
        val id = "g-${UUID.randomUUID()}"
        val staging = File(generations, ".stage-$id")
        check(staging.mkdir()) { "无法创建规则集临时目录" }
        val stagedFiles = fileNames.map { File(staging, it) }
        val destination = File(generations, id)
        var prepared = false
        try {
            write(stagedFiles)
            validate(stagedFiles)
            val hashes = stagedFiles.map(::sha256)
            val metadata = Properties().apply {
                setProperty("schema", SCHEMA)
                setProperty("updatedAtMillis", updatedAtMillis.toString())
                fileNames.forEachIndexed { index, name ->
                    setProperty("$name.bytes", stagedFiles[index].length().toString())
                    setProperty("$name.sha256", hashes[index])
                }
            }
            writeSynced(File(staging, "manifest.properties"), metadata)
            // Persist this before the directory rename: a candidate may already be referenced
            // by a starting core after process death, even though active still points to old.
            writeSynced(File(staging, PREPARED_MARKER), Properties().apply { setProperty("generation", id) })
            stagedFiles.forEach { FileOutputStream(it, true).use { stream -> stream.fd.sync() } }
            directorySync(staging)

            synchronized(readLock) {
                val old = currentLocked()
                if (old != null && verified[old.generation]?.hashes == hashes) {
                    return@synchronized Installation(old, false)
                }
                cleanUnreferencedLocked()
                val storedBytes = generations.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                require(storedBytes <= maxStoredBytes) { "规则集保留空间已满，请重启 RRBOX 后再更新" }
                atomicMove(staging, destination)
                directorySync(generations)
                prepared = true
                val files = fileNames.map { File(destination, it) }
                verified[id] = Verified(stamp(files), hashes)
                issued += id
                Installation(Snapshot(id, files, updatedAtMillis), true)
            }
        } finally {
            staging.deleteRecursively()
            if (!prepared) destination.deleteRecursively()
        }
    }

    /**
     * Publish only after the caller has accepted the candidate (and, when running, its core
     * startup). The expected generation also makes rollback safe against a later update.
     * False means another update won; no pointer is changed. Invalid candidates throw.
     */
    fun activate(candidate: Snapshot, expectedGeneration: String?): Boolean = synchronized(writeLock) {
        synchronized(readLock) {
            val old = currentLocked()
            if (old?.generation != expectedGeneration) return@synchronized false
            require(validGeneration(candidate.generation, force = true)) { "待启用的规则集校验失败" }
            val canonical = snapshotLocked(candidate.generation)
            require(candidate.files.map(File::getAbsolutePath) == canonical.files.map(File::getAbsolutePath)) {
                "待启用的规则集路径不匹配"
            }
            // Full hash checks attest the exact bytes validated during prepare(). Do not
            // decode the SRS again on the service's short activation commit path.
            if (old?.generation == candidate.generation) return@synchronized true
            val oldPointer = readPointer(activeFile)?.takeIf { it.current == old?.generation }
                ?: old?.let { Pointer(it.generation, null, it.updatedAtMillis) }
            oldPointer?.let { writePointer(backupFile, it) }
            writePointer(activeFile, Pointer(canonical.generation, old?.generation, canonical.updatedAtMillis))
            issued += canonical.generation
            // A crash before this deletion only pins one extra generation; it cannot expose
            // incomplete content or cause recovery to select an unactivated candidate.
            File(File(generations, canonical.generation), PREPARED_MARKER).delete()
            true
        }
    }

    /** Resolve a persisted transaction using canonical paths, never paths supplied by callers. */
    fun snapshot(generation: String): Snapshot? = synchronized(readLock) {
        if (!validGeneration(generation, force = true)) return@synchronized null
        issued += generation
        snapshotLocked(generation)
    }

    /** Recovery may release orphan candidates after excluding its persisted transaction/runtime. */
    fun preparedGenerations(): List<String> = synchronized(readLock) {
        generations.listFiles { file ->
            GENERATION.matches(file.name) && file.isDirectory && File(file, PREPARED_MARKER).isFile
        }?.map { it.name }.orEmpty()
    }

    /** Release an abandoned candidate; paths issued to a runtime remain readable this process. */
    fun discardPrepared(generation: String) = synchronized(writeLock) {
        synchronized(readLock) {
            require(GENERATION.matches(generation))
            File(File(generations, generation), PREPARED_MARKER).delete()
            cleanUnreferencedLocked()
        }
    }

    private fun currentLocked(): Snapshot? {
        val active = readPointer(activeFile)
        if (active != null && validGeneration(active.current)) {
            issued += active.current
            return snapshot(active.current, active.updatedAtMillis)
        }
        val backup = readPointer(backupFile)
        val recovery = listOfNotNull(active?.previous, backup?.current, backup?.previous)
            .distinct().firstOrNull { validGeneration(it) } ?: return null
        val recovered = Pointer(recovery, null, snapshotLocked(recovery).updatedAtMillis)
        // Returning a verified previous pair remains safe even on a read-only/full filesystem.
        runCatching { writePointer(activeFile, recovered) }
        issued += recovery
        return snapshot(recovery, recovered.updatedAtMillis)
    }

    private fun snapshot(id: String, updatedAtMillis: Long) =
        Snapshot(id, fileNames.map { File(File(generations, id), it) }, updatedAtMillis)

    private fun snapshotLocked(id: String): Snapshot {
        val metadata = readProperties(File(File(generations, id), "manifest.properties"), 4096L)
        val timestamp = metadata?.getProperty("updatedAtMillis")?.toLongOrNull()
            ?: listOfNotNull(readPointer(activeFile), readPointer(backupFile))
                .firstOrNull { it.current == id }?.updatedAtMillis ?: 0L
        return snapshot(id, timestamp)
    }

    private fun validGeneration(id: String, force: Boolean = false): Boolean = runCatching {
        require(GENERATION.matches(id))
        val root = File(generations, id)
        val files = fileNames.map { File(root, it) }
        require(files.all { it.isFile })
        val fileStamp = stamp(files)
        if (!force && verified[id]?.stamp == fileStamp) return@runCatching true
        val metadata = readProperties(File(root, "manifest.properties"), 4096L) ?: return@runCatching false
        require(metadata.getProperty("schema") == SCHEMA)
        val hashes = files.mapIndexed { index, file ->
            require(metadata.getProperty("${fileNames[index]}.bytes")?.toLongOrNull() == file.length())
            val expected = metadata.getProperty("${fileNames[index]}.sha256")
            val actual = sha256(file)
            require(actual == expected)
            actual
        }
        // SHA256 proves these are exactly the bytes decoded by the validator at preparation.
        verified[id] = Verified(fileStamp, hashes)
        true
    }.getOrDefault(false)

    private fun cleanUnreferencedLocked() {
        val pointers = listOfNotNull(readPointer(activeFile), readPointer(backupFile))
        val keep = pointers.flatMap { listOfNotNull(it.current, it.previous) }.toMutableSet()
        keep += issued
        val pinnedPaths = runCatching { protectedPaths() }.getOrElse { return }
        generations.listFiles { f -> GENERATION.matches(f.name) && f.isDirectory }?.forEach { generation ->
            if (generation.name !in keep && !File(generation, PREPARED_MARKER).isFile &&
                fileNames.none { File(generation, it).absolutePath in pinnedPaths }) {
                if (generation.deleteRecursively()) verified.remove(generation.name)
            }
        }
    }

    private fun readPointer(file: File): Pointer? = runCatching {
        val properties = readProperties(file, 1024L) ?: return null
        val current = properties.getProperty("current") ?: return null
        val previous = properties.getProperty("previous")?.takeIf { it.isNotBlank() }
        require(GENERATION.matches(current) && (previous == null || GENERATION.matches(previous)))
        Pointer(current, previous, properties.getProperty("updatedAtMillis")?.toLongOrNull() ?: 0L)
    }.getOrNull()

    private fun writePointer(file: File, pointer: Pointer) {
        val temporary = File(directory, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            writeSynced(temporary, Properties().apply {
                setProperty("current", pointer.current)
                setProperty("previous", pointer.previous.orEmpty())
                setProperty("updatedAtMillis", pointer.updatedAtMillis.toString())
            })
            atomicMove(temporary, file)
            // Rename already committed. A later directory-fsync error must not delete the published pair.
            runCatching { directorySync(directory) }
        } finally {
            temporary.delete()
        }
    }

    private fun readProperties(file: File, maxBytes: Long): Properties? {
        if (!file.isFile || file.length() !in 1L..maxBytes) return null
        return Properties().apply { file.inputStream().use { load(it) } }
    }

    private fun writeSynced(file: File, value: Properties) {
        FileOutputStream(file).use { output ->
            value.store(output, null)
            output.fd.sync()
        }
    }

    private fun stamp(files: List<File>) = files.map { it.length() to it.lastModified() }

    companion object {
        // Bump when changing the native validator/core, so old attestations are not silently trusted.
        private const val SCHEMA = "sing-box-1.14-srs-zlib-v1"
        private const val PREPARED_MARKER = "prepared.properties"
        private val GENERATION = Regex("g-[a-f0-9-]{36}")

        private fun moveAtomically(source: File, target: File) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

        private fun syncDirectory(directory: File) {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
