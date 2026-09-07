package com.rr.client.routing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Inflater

/** Bounded container verification, followed by the bundled core's actual rule decoder. */
internal object SrsRuleSetValidation {
    const val MAX_FILE_BYTES = 8L * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 64L * 1024 * 1024
    private const val MAX_SUPPORTED_VERSION = 5

    fun validate(files: List<File>, checkConfig: (String) -> Unit) {
        files.forEach(::validateContainer)
        checkConfig(validationConfig(files))
    }

    fun validateContainer(file: File) {
        require(file.isFile && file.length() in 8L..MAX_FILE_BYTES) { "规则集文件大小不合法：${file.name}" }
        file.inputStream().buffered().use { input ->
            require(input.read() == 0x53 && input.read() == 0x52 && input.read() == 0x53) { "规则集文件头无效" }
            require(input.read() in 1..MAX_SUPPORTED_VERSION) { "规则集版本超过当前内核支持范围" }
            val inflater = Inflater()
            try {
                val compressed = ByteArray(8192)
                val expanded = ByteArray(8192)
                var total = 0L
                while (!inflater.finished()) {
                    if (inflater.needsInput()) {
                        val count = input.read(compressed)
                        require(count > 0) { "规则集压缩内容被截断" }
                        inflater.setInput(compressed, 0, count)
                    }
                    val count = inflater.inflate(expanded)
                    if (total == 0L && count > 0) {
                        // The first field is the top-level rule count (uvarint).
                        require((expanded[0].toInt() and 0xff) != 0) { "拒绝使用空规则集替换国内分流规则" }
                    }
                    total += count
                    require(total <= MAX_EXPANDED_BYTES) { "规则集解压大小超过限制" }
                    require(!inflater.needsDictionary()) { "不支持带字典的规则集" }
                    require(count > 0 || inflater.needsInput() || inflater.finished()) { "规则集压缩内容无效" }
                }
                require(total > 0 && inflater.remaining == 0 && input.read() == -1) { "规则集包含空数据或多余尾部内容" }
            } finally {
                inflater.end()
            }
        }
    }

    /**
     * v1.14: Libbox.checkConfig -> box.New -> Router.Initialize -> NewLocalRuleSet
     * -> reloadFile -> srs.Read + NewHeadlessRule. No service/network is started.
     * The zlib pass above also checks its trailer, which srs.Read need not consume.
     */
    fun validationConfig(files: List<File>): String = JsonObject().apply {
        add("log", JsonObject().apply { addProperty("disabled", true) })
        add("outbounds", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "direct")
                addProperty("tag", "validation-direct")
            })
        })
        add("route", JsonObject().apply {
            addProperty("final", "validation-direct")
            add("rule_set", JsonArray().apply {
                files.forEachIndexed { index, file ->
                    add(JsonObject().apply {
                        addProperty("type", "local")
                        addProperty("tag", "validation-$index")
                        addProperty("format", "binary")
                        addProperty("path", file.absolutePath)
                    })
                }
            })
        })
    }.toString()

    fun copyBounded(input: InputStream, output: OutputStream, ensureActive: () -> Unit = {}): Long {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            ensureActive()
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
            require(total <= MAX_FILE_BYTES) { "规则集下载超过 8 MiB 限制" }
            output.write(buffer, 0, count)
        }
    }
}
