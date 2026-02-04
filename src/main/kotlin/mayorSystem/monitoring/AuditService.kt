package mayorSystem.monitoring

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import mayorSystem.MayorPlugin
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
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
        val event = AuditEvent(
            timestamp = OffsetDateTime.now(),
            actorUuid = actorUuid,
            actorName = actorName,
            action = action,
            term = term,
            target = target,
            details = details
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

    private fun appendToFile(event: AuditEvent) {
        try {
            if (!file.parentFile.exists()) file.parentFile.mkdirs()
            FileWriter(file, true).use { fw ->
                fw.write(toJsonLine(event))
                fw.write("\n")
            }
        } catch (t: Throwable) {
            plugin.logger.warning("Failed to write audit.log: ${t.message}")
        }
    }

    private fun toJsonLine(e: AuditEvent): String {
        val o = JsonObject()
        o.addProperty("timestamp", e.timestamp.toString())
        if (e.actorUuid != null) o.addProperty("actorUuid", e.actorUuid)
        o.addProperty("actorName", e.actorName)
        o.addProperty("action", e.action)
        if (e.term != null) o.addProperty("term", e.term)
        if (e.target != null) o.addProperty("target", e.target)
        if (e.details.isNotEmpty()) {
            val d = JsonObject()
            for ((k, v) in e.details) d.addProperty(k, v)
            o.add("details", d)
        }
        return gson.toJson(o)
    }

    private fun loadTail() {
        if (!file.exists()) return

        // Read entire file lines, keep only the last ringSize.
        // For typical server sizes this is fine; JSONL keeps it compact.
        val tail = ArrayDeque<String>(ringSize + 1)
        try {
            BufferedReader(FileReader(file)).use { br ->
                while (true) {
                    val line = br.readLine() ?: break
                    if (line.isBlank()) continue
                    tail.addLast(line)
                    while (tail.size > ringSize) tail.removeFirst()
                }
            }
        } catch (_: Throwable) {
            return
        }

        val parsed = tail.mapNotNull(::parseLine)
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

            AuditEvent(ts, actorUuid, actorName, action, term, target, details)
        }.getOrNull()
    }
}

