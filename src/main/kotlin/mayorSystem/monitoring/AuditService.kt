package mayorSystem.monitoring

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import mayorSystem.MayorPlugin
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.OffsetDateTime
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class AuditService(private val plugin: MayorPlugin) {

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val lock = ReentrantLock()
    private val file: File = File(plugin.dataFolder, "audit.log")
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MayorAuditWriter").apply { isDaemon = true }
    }

    private val ringSize: Int
        get() = plugin.config.getInt("admin.audit.ring_size", 200).coerceIn(50, 5000)

    private val maxBytes: Long
        get() = plugin.config.getLong("admin.audit.max_bytes", 5_000_000).coerceAtLeast(200_000)

    private val tailMaxBytes: Long
        get() = plugin.config.getLong("admin.audit.tail_max_bytes", 1_000_000).coerceAtLeast(50_000)

    private val buffer: ArrayDeque<AuditEvent> = ArrayDeque()

    init {
        // Best-effort load of the last N events.
        loadTail()
    }

    fun recent(): List<AuditEvent> = lock.withLock { buffer.toList() }

    fun log(
        actorUuid: String?,
        actorName: String,
        action: String,
        term: Int? = null,
        target: String? = null,
        details: Map<String, String> = emptyMap()
    ) {
        val safeDetails = redactDetails(action, details)
        val event = AuditEvent(
            timestamp = OffsetDateTime.now(),
            actorUuid = actorUuid,
            actorName = actorName,
            action = action,
            term = term,
            target = target,
            details = safeDetails
        )

        // Keep memory buffer consistent.
        lock.withLock {
            buffer.addLast(event)
            while (buffer.size > ringSize) buffer.removeFirst()
        }

        // File write off-thread (single-writer to preserve ordering).
        writer.execute { appendToFile(event) }
    }

    fun shutdown() {
        writer.shutdown()
        runCatching { writer.awaitTermination(5, TimeUnit.SECONDS) }
    }

    fun export(
        events: List<AuditEvent>,
        filenamePrefix: String = "audit-export"
    ): File {
        val ts = OffsetDateTime.now().toString().replace(":", "-")
        val out = File(plugin.dataFolder, "$filenamePrefix-$ts.jsonl")
        if (!out.parentFile.exists()) out.parentFile.mkdirs()

        FileWriter(out, false).use { fw ->
            for (e in events) {
                fw.write(toJsonLine(e))
                fw.write("\n")
            }
        }
        return out
    }

    // ---------------------------------------------------------------------


    private fun rotateIfNeeded() {
        if (!file.exists()) return
        if (file.length() < maxBytes) return
        val ts = OffsetDateTime.now().toString().replace(":", "-")
        val rotated = File(file.parentFile, "audit-$ts.log")
        try {
            Files.move(file.toPath(), rotated.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Throwable) {
            // Best-effort rotation; keep writing to current file if it fails.
        }
    }

    private fun appendToFile(event: AuditEvent) {
        try {
            if (!file.parentFile.exists()) file.parentFile.mkdirs()
            rotateIfNeeded()
            FileWriter(file, true).use { fw ->
                fw.write(toJsonLine(event))
                fw.write("\n")
            }
        } catch (t: Throwable) {
            plugin.logger.warning("Failed to write audit.log: ${t.message}")
        }
    }

    private fun toJsonLine(e: AuditEvent): String {
        val safeDetails = redactDetails(e.action, e.details)
        val o = JsonObject()
        o.addProperty("timestamp", e.timestamp.toString())
        if (e.actorUuid != null) o.addProperty("actorUuid", e.actorUuid)
        o.addProperty("actorName", e.actorName)
        o.addProperty("action", e.action)
        if (e.term != null) o.addProperty("term", e.term)
        if (e.target != null) o.addProperty("target", e.target)
        if (safeDetails.isNotEmpty()) {
            val d = JsonObject()
            for ((k, v) in safeDetails) d.addProperty(k, v)
            o.add("details", d)
        }
        return gson.toJson(o)
    }

    private fun loadTail() {
        if (!file.exists()) return

        val lines = readTailLines(file, ringSize, tailMaxBytes)
        val parsed = lines.mapNotNull(::parseLine)
        lock.withLock {
            buffer.clear()
            buffer.addAll(parsed)
        }
    }

    private fun parseLine(line: String): AuditEvent? {
        return runCatching {
            val obj = gson.fromJson(line, JsonObject::class.java)
            val ts = OffsetDateTime.parse(obj.get("timestamp").asString)
            val actorUuid = obj.get("actorUuid")?.asString
            val actorName = obj.get("actorName")?.asString ?: "Unknown"
            val action = obj.get("action")?.asString ?: "UNKNOWN"
            val term = obj.get("term")?.asInt
            val target = obj.get("target")?.asString

            val details = mutableMapOf<String, String>()
            val det = obj.getAsJsonObject("details")
            if (det != null) {
                for ((k, v) in det.entrySet()) {
                    details[k] = v.asString
                }
            }

            AuditEvent(ts, actorUuid, actorName, action, term, target, redactDetails(action, details))
        }.getOrNull()
    }

    private fun redactDetails(action: String, details: Map<String, String>): Map<String, String> {
        if (details.isEmpty()) return details

        val out = LinkedHashMap<String, String>(details)
        if (action.equals("CONFIG_SET", ignoreCase = true)) {
            val path = details["path"].orEmpty()
            if (isSensitiveConfigPath(path)) {
                if (out.containsKey("from")) out["from"] = "<redacted>"
                if (out.containsKey("to")) out["to"] = "<redacted>"
            }
        }

        for ((key, value) in details) {
            if (isSensitiveDetailKey(key)) {
                out[key] = "<redacted>"
            } else {
                out.putIfAbsent(key, value)
            }
        }
        return out
    }

    private fun isSensitiveConfigPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.contains("password") ||
            lower.contains("secret") ||
            lower.contains("token") ||
            lower.contains("webhook") ||
            lower.endsWith(".key") ||
            lower.contains("_key")
    }

    private fun isSensitiveDetailKey(key: String): Boolean {
        val lower = key.lowercase()
        return lower.contains("password") ||
            lower.contains("secret") ||
            lower.contains("token") ||
            lower.contains("webhook")
    }
    private fun readTailLines(file: File, maxLines: Int, maxBytes: Long): List<String> {
        if (maxLines <= 0) return emptyList()
        val length = file.length()
        if (length <= 0) return emptyList()
        val bytesToRead = minOf(maxBytes, length)
        val lines = ArrayDeque<String>()
        RandomAccessFile(file, "r").use { raf ->
            var pos = length - 1
            var read = 0L
            val sb = StringBuilder()
            while (pos >= 0 && read < bytesToRead && lines.size < maxLines) {
                raf.seek(pos)
                val b = raf.readByte()
                read++
                if (b.toInt() == 0x0A) {
                    if (sb.isNotEmpty()) {
                        lines.addFirst(sb.reverse().toString())
                        sb.setLength(0)
                    }
                } else if (b.toInt() != 0x0D) {
                    sb.append(b.toInt().toChar())
                }
                pos--
            }
            if (sb.isNotEmpty() && lines.size < maxLines) {
                lines.addFirst(sb.reverse().toString())
            }
        }
        return lines.toList()
    }

}

