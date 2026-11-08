package app.bekocat.core.timeline

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Portable .bekocat package: project.json + referenced media (and fonts/LUTs) in a zip.
 * Import is defensive: rejects path traversal, absolute paths, symlink-like names, too many
 * entries, oversized entries and suspicious compression ratios (zip bombs), and never
 * executes anything from the package.
 */
object ProjectPackage {
    const val MAX_ENTRIES = 5000
    const val MAX_TOTAL_BYTES = 32L * 1024 * 1024 * 1024
    const val MAX_PROJECT_JSON = 64L * 1024 * 1024
    const val MAX_RATIO = 200.0

    class PackageException(msg: String) : Exception(msg)

    fun safeName(name: String): String = name.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('.').take(80).ifEmpty { "file" }

    /** Writes the package. [open] returns a stream for an asset's media (null = skip, kept as external reference). */
    fun export(p: Project, out: OutputStream, open: (Asset) -> InputStream?) {
        val renamed = ArrayList<Asset>()
        ZipOutputStream(out).use { zip ->
            for (a in p.assets) {
                val input = open(a)
                if (input == null) { renamed += a; continue }
                val path = "media/${a.id}_${safeName(a.displayName)}"
                zip.putNextEntry(ZipEntry(path))
                input.use { it.copyTo(zip) }
                zip.closeEntry()
                renamed += a.copy(uri = "package:$path")
            }
            zip.putNextEntry(ZipEntry("project.json"))
            zip.write(ProjectCodec.encode(p.copy(assets = renamed)).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"format":"bekocat.package","version":1,"schemaVersion":$CURRENT_SCHEMA_VERSION}""".toByteArray())
            zip.closeEntry()
        }
    }

    fun validateEntryName(name: String) {
        if (name.isEmpty() || name.length > 512) throw PackageException("Invalid entry name")
        if (name.startsWith("/") || name.startsWith("\\") || Regex("^[A-Za-z]:").containsMatchIn(name)) throw PackageException("Absolute path in package: $name")
        val parts = name.replace('\\', '/').split('/')
        if (parts.any { it == ".." }) throw PackageException("Path traversal in package: $name")
        if (!(name == "project.json" || name == "manifest.json" || name.startsWith("media/"))) throw PackageException("Unexpected entry: $name")
    }

    /**
     * Extracts into [destDir] and returns the project with media URIs rewritten to local files.
     * [newId] lets the caller import a copy without clobbering an existing project.
     */
    fun import(input: InputStream, destDir: File, newId: String): Project {
        destDir.mkdirs()
        val canonicalRoot = destDir.canonicalFile
        var projectJson: String? = null
        var entries = 0
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (++entries > MAX_ENTRIES) throw PackageException("Too many entries")
                validateEntryName(e.name)
                if (e.isDirectory) continue
                val target = File(destDir, e.name).canonicalFile
                if (!target.path.startsWith(canonicalRoot.path + File.separator)) throw PackageException("Entry escapes destination: ${e.name}")
                val limit = if (e.name == "project.json") MAX_PROJECT_JSON else MAX_TOTAL_BYTES - total
                var written = 0L
                val compressed = e.compressedSize.takeIf { it > 0 }
                if (e.name == "project.json" || e.name == "manifest.json") {
                    val bytes = readBounded(zip, limit)
                    written = bytes.size.toLong()
                    if (e.name == "project.json") projectJson = String(bytes, Charsets.UTF_8)
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { os ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = zip.read(buf); if (n < 0) break
                            written += n
                            if (written > limit) throw PackageException("Package too large")
                            if (compressed != null && written > 1_000_000 && written / compressed.toDouble() > MAX_RATIO) throw PackageException("Suspicious compression ratio (zip bomb)")
                            os.write(buf, 0, n)
                        }
                    }
                }
                total += written
            }
        }
        val p = ProjectCodec.decode(projectJson ?: throw PackageException("project.json missing"))
        val assets = p.assets.map { a ->
            if (a.uri.startsWith("package:")) {
                val rel = a.uri.removePrefix("package:")
                validateEntryName(rel)
                val f = File(destDir, rel).canonicalFile
                if (!f.exists() || !f.path.startsWith(canonicalRoot.path)) throw PackageException("Missing media ${a.displayName}")
                a.copy(uri = f.toURI().toString())
            } else a
        }
        return p.copy(id = newId, assets = assets)
    }

    private fun readBounded(input: InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = input.read(buf); if (n < 0) break
            total += n
            if (total > limit) throw PackageException("Entry too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
