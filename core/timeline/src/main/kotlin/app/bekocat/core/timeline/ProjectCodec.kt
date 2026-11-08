package app.bekocat.core.timeline

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.Json as KJson

/**
 * Versioned .bekocat project schema. Input is first validated by the bounded
 * [Json] parser (depth/size limits), migrated forward on the JSON tree, then
 * decoded. Newer schema versions are refused rather than silently truncated.
 */
object ProjectCodec {

    private val json = KJson {
        encodeDefaults = false
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }
    private val compact = KJson { encodeDefaults = false; ignoreUnknownKeys = true; explicitNulls = false }

    fun encode(p: Project, pretty: Boolean = true): String {
        val tree = (if (pretty) json else compact).encodeToJsonElement(Project.serializer(), p.copy(schemaVersion = CURRENT_SCHEMA_VERSION)).jsonObject
        val withFormat = buildJsonObject {
            put("format", JsonPrimitive("bekocat.project"))
            put("schemaVersion", JsonPrimitive(CURRENT_SCHEMA_VERSION))
            tree.forEach { (k, v) -> if (k != "schemaVersion") put(k, v) }
        }
        return (if (pretty) json else compact).encodeToString(JsonObject.serializer(), withFormat)
    }

    fun decode(text: String): Project {
        // Bounded structural validation first (rejects deep nesting / huge input).
        Json.parse(text)
        val root = try { json.parseToJsonElement(text) } catch (e: Exception) { throw JsonException("Invalid JSON: ${e.message}") }
        if (root !is JsonObject) throw JsonException("Root must be an object")
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: 1
        if (version > CURRENT_SCHEMA_VERSION) throw JsonException("Project was saved by a newer Bekocat (schema $version)")
        val migrated = migrate(root, version)
        return try {
            json.decodeFromJsonElement(Project.serializer(), migrated).copy(schemaVersion = CURRENT_SCHEMA_VERSION)
        } catch (e: IllegalArgumentException) {
            throw JsonException("Invalid project: ${e.message}")
        }
    }

    // ---------- migrations (operate on the JSON tree) ----------
    private fun migrate(root: JsonObject, from: Int): JsonObject {
        var r = root
        var v = from
        while (v < CURRENT_SCHEMA_VERSION) {
            r = when (v) {
                1 -> migrate1to2(r)
                2 -> migrate2to3(r)
                else -> r
            }
            v++
        }
        return r
    }

    private fun JsonObject.with(vararg kv: Pair<String, JsonElement?>): JsonObject {
        val m = LinkedHashMap<String, JsonElement>(this)
        for ((k, v) in kv) if (v == null) m.remove(k) else m[k] = v
        return JsonObject(m)
    }

    private fun mapClips(root: JsonObject, f: (JsonObject) -> JsonObject): JsonObject {
        fun seq(s: JsonObject): JsonObject {
            val tracks = (s["tracks"] as? JsonArray)?.map { t ->
                val to = t.jsonObject
                to.with("clips" to JsonArray((to["clips"] as? JsonArray)?.map { f(it.jsonObject) } ?: emptyList()))
            } ?: emptyList()
            return s.with("tracks" to JsonArray(tracks))
        }
        var r = root
        (root["sequence"] as? JsonObject)?.let { r = r.with("sequence" to seq(it)) }
        (root["compositions"] as? JsonArray)?.let { arr -> r = r.with("compositions" to JsonArray(arr.map { seq(it.jsonObject) })) }
        return r
    }

    /** v1 stored clip speed as a decimal number; v2 stores an exact rational string. */
    private fun migrate1to2(r: JsonObject): JsonObject = mapClips(r) { c ->
        val sp = (c["speed"] as? JsonPrimitive)
        if (sp != null && !sp.isString && sp.doubleOrNull != null) c.with("speed" to JsonPrimitive(Rational.fromDouble(sp.doubleOrNull!!).toString())) else c
    }.with("schemaVersion" to JsonPrimitive(2))

    /** v2 → v3: short keyframe keys renamed; clip audio fields moved into `audio`. */
    private fun migrate2to3(r: JsonObject): JsonObject {
        val renamed = renameKeyframes(r) as JsonObject
        return mapClips(renamed) { c ->
            val audio = buildJsonObject {
                c["volume"]?.let { put("volume", it) }
                c["fadeIn"]?.let { put("fadeIn", it) }
                c["fadeOut"]?.let { put("fadeOut", it) }
                c["preservePitch"]?.let { put("preservePitch", it) }
            }
            var o = c.with("volume" to null, "fadeIn" to null, "fadeOut" to null, "preservePitch" to null)
            if (audio.isNotEmpty()) o = o.with("audio" to audio)
            if ((c["text"] as? JsonObject) != null) o = o.with("kind" to JsonPrimitive("TEXT"))
            o
        }.with("schemaVersion" to JsonPrimitive(3))
    }

    private val kfKeys = mapOf("t" to "time", "v" to "value", "i" to "interpolation", "ox" to "outX", "oy" to "outY", "ix" to "inX", "iy" to "inY")

    private fun renameKeyframes(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> {
            val isKf = e.containsKey("t") && e.containsKey("v")
            JsonObject(e.entries.associate { (k, v) -> (if (isKf) kfKeys[k] ?: k else k) to renameKeyframes(v) })
        }
        is JsonArray -> JsonArray(e.map { renameKeyframes(it) })
        else -> e
    }

    /** Reads a string field from raw JSON (used by listing without full decode). */
    fun peekName(text: String): String? = runCatching { json.parseToJsonElement(text).jsonObject["name"]?.jsonPrimitive?.contentOrNull }.getOrNull()
}

/**
 * Crash-safe project storage: journal of committed edits + atomic snapshot.
 * Save = write temp, fsync, rotate the previous good version to .bak, rename.
 * Periodic timestamped backups are kept (last [keepBackups]). Load falls back
 * to .bak then to the newest readable backup if the main file is corrupt.
 */
class ProjectStore(private val root: File, private val keepBackups: Int = 5) {

    fun dirOf(projectId: String): File {
        require(projectId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid project id" }
        return File(root, projectId)
    }

    private fun main(id: String) = File(dirOf(id), "project.json")
    private fun backup(id: String) = File(dirOf(id), "project.json.bak")
    private fun backupsDir(id: String) = File(dirOf(id), "backups")
    fun journal(id: String) = File(dirOf(id), "journal.log")

    fun save(p: Project) {
        val dir = dirOf(p.id).apply { mkdirs() }
        val tmp = File(dir, "project.json.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(ProjectCodec.encode(p).toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        val m = main(p.id)
        if (m.exists()) {
            val ok = runCatching { ProjectCodec.decode(m.readText()) }.isSuccess
            if (ok) m.copyTo(backup(p.id), overwrite = true)
        }
        if (!tmp.renameTo(m)) {
            m.delete()
            if (!tmp.renameTo(m)) throw java.io.IOException("Could not commit project file")
        }
        // Journal is truncated after a successful snapshot.
        journal(p.id).delete()
    }

    /** Appends a one-line record of a committed edit before the debounced snapshot is written. */
    fun appendJournal(id: String, label: String, timeMs: Long) {
        val f = journal(id)
        f.parentFile?.mkdirs()
        f.appendText("$timeMs\t${label.replace('\n', ' ').take(200)}\n")
    }

    /** Edits that were committed after the last snapshot (shown after a crash). */
    fun pendingJournal(id: String): List<String> = journal(id).takeIf { it.exists() }?.readLines().orEmpty()

    fun periodicBackup(p: Project, nowMs: Long) {
        val d = backupsDir(p.id).apply { mkdirs() }
        File(d, "project-$nowMs.json").writeText(ProjectCodec.encode(p, pretty = false))
        d.listFiles()?.sortedByDescending { it.name }?.drop(keepBackups)?.forEach { it.delete() }
    }

    fun backups(id: String): List<File> = backupsDir(id).listFiles()?.sortedByDescending { it.name }.orEmpty()

    fun load(id: String): Project {
        val candidates = listOf(main(id), backup(id)) + backups(id)
        var first: Throwable? = null
        for (f in candidates) {
            if (!f.exists()) continue
            try { return ProjectCodec.decode(f.readText()) } catch (e: Throwable) { if (first == null) first = e }
        }
        throw first ?: java.io.FileNotFoundException("Project $id not found")
    }

    fun list(): List<Project> = (root.listFiles() ?: emptyArray())
        .filter { it.isDirectory }
        .mapNotNull { runCatching { load(it.name) }.getOrNull() }
        .sortedByDescending { it.modifiedAtMs }

    fun delete(id: String) { dirOf(id).deleteRecursively() }
}
