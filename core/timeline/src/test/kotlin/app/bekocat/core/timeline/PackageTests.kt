package app.bekocat.core.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackageTest {
    private fun tmp() = File(System.getProperty("java.io.tmpdir"), "bk-${System.nanoTime()}").apply { mkdirs() }

    @Test fun roundTripRewritesMediaUris() {
        val p = Project.create("p1", "حزمة", 1).copy(assets = listOf(Asset("a1", "content://x/1", AssetKind.VIDEO, "clip 1.mp4", Rational.seconds(2))))
        val bos = ByteArrayOutputStream()
        ProjectPackage.export(p, bos) { ByteArrayInputStream(ByteArray(1000) { 7 }) }
        val dir = tmp()
        val back = ProjectPackage.import(ByteArrayInputStream(bos.toByteArray()), dir, "p2")
        assertEquals("p2", back.id)
        assertEquals("حزمة", back.name)
        val f = File(java.net.URI(back.assets[0].uri))
        assertTrue(f.exists()); assertEquals(1000L, f.length())
        dir.deleteRecursively()
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bos.toByteArray()
    }

    @Test fun rejectsTraversalAndBombs() {
        val bad = listOf("../evil.txt", "media/../../evil", "/abs/path", "C:/win", "other/file")
        for (n in bad) {
            try { ProjectPackage.import(ByteArrayInputStream(zipOf(n to ByteArray(4))), tmp(), "x"); fail("accepted $n") }
            catch (_: ProjectPackage.PackageException) {}
        }
        // 50 MB of zeros compresses ~1000:1 → rejected as a zip bomb.
        val bomb = zipOf("media/zeros.bin" to ByteArray(50 * 1024 * 1024))
        try { ProjectPackage.import(ByteArrayInputStream(bomb), tmp(), "x"); fail("bomb accepted") } catch (_: ProjectPackage.PackageException) {}
    }
}

class TemplateTest {
    @Test fun allTemplatesBuildLayeredValidProjects() {
        assertTrue(TemplateCatalog.all.size >= 25)
        for (t in TemplateCatalog.all) {
            val p = TemplateCatalog.build(t.id, "p", 0)
            // Valid: no overlaps on any track, decodes after encode, renders a plan with ≥ 2 layers somewhere.
            for (tr in p.sequence.tracks) {
                val s = tr.clips.sortedBy { it.timelineStart }
                for (i in 1 until s.size) assertTrue("${t.id}/${tr.id} overlap", s[i].timelineStart >= s[i - 1].timelineEnd)
            }
            assertEquals(p, ProjectCodec.decode(ProjectCodec.encode(p)))
            val maxLayers = (0..20).maxOf { k -> RenderPlanner.plan(p, p.sequence.duration * Rational.of(k.toLong(), 21)).items.size }
            assertTrue("${t.id} is flat", maxLayers >= 2)
        }
    }

    @Test fun placeholderReplacementKeepsTiming() {
        val p = TemplateCatalog.build("reels-hook", "p", 0)
        val ph = TemplateCatalog.placeholders(p).first()
        val a = Asset("m", "content://m", AssetKind.VIDEO, "m.mp4", Rational.seconds(30), 1080, 1920)
        val ed = TimelineEditor(mapOf("m" to a), CounterIds())
        val s = ed.replaceMedia(p.sequence, ph.id, a, keepAttributes = true)
        val c = s.findClip(ph.id)!!.second
        assertEquals(ClipKind.MEDIA, c.kind); assertEquals(ph.timelineStart, c.timelineStart); assertEquals(ph.duration, c.duration)
    }
}
