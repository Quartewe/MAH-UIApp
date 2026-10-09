package com.aliothmoon.maafw.project

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ResourceProtocolTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = ResourceProtocolFixture(temporary.newFolder())

    @Test fun `python full and multistep hotfix inventories are accepted and commit only final version`() {
        val f = fixture(); val root = f.root(); val installer = ProjectPackageInstaller(root)
        installer.installResources(listOf(f.archive("A", "full")))
        installer.installResources(listOf(f.archive("B", "hotfix"), f.archive("C", "hotfix")))
        assertEquals("C", installer.state().resourceVersion)
        assertEquals(f.descriptor("C").contentId, installer.verifiedResourceContentId())
        assertFalse(File(root, "resource/base/image/character/old.png").exists())
        assertEquals("new", File(root, "resource/base/image/character/new.png").readText())
        assertEquals("user combat", File(root, "data/custom.json").readText())
        assertEquals("user configuration", File(root, "config/user.json").readText())
        assertEquals("project UI", File(root, "resource/base/image/own.png").readText())
    }

    @Test fun `legacy inventory can serve as baseline only after full hash verification`() {
        val f = fixture(); val root = f.root(); val installer = ProjectPackageInstaller(root)
        installer.installResources(listOf(f.archive("A", "full")))
        File(root, ProjectPackageInstaller.STATE).writeText(Json.encodeToString(installer.state().copy(resourceSizes = emptyMap(), resourceContentId = "")))
        assertEquals(f.descriptor("A").contentId, installer.verifiedResourceContentId())
        installer.installResources(listOf(f.archive("B", "hotfix")))
        assertEquals(f.descriptor("B").contentId, installer.verifiedResourceContentId())
    }

    @Test fun `failure in second delta rolls back the entire chain`() {
        val f = fixture(); val root = f.root(); val installer = ProjectPackageInstaller(root)
        installer.installResources(listOf(f.archive("A", "full")))
        val before = installer.state()
        val broken = rewrite(f.archive("C", "hotfix"), mapOf("image/ar/new.png" to "broken".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) {
            installer.installResources(listOf(f.archive("B", "hotfix"), broken))
        }
        assertEquals(before, installer.state())
        assertTrue(File(root, "resource/base/image/character/old.png").exists())
        assertFalse(File(root, "resource/base/image/character/new.png").exists())
        assertTrue(installer.resourcesComplete(true))
    }

    @Test fun `wrong or corrupt baseline cannot apply delta`() {
        val f = fixture(); val root = f.root(); val installer = ProjectPackageInstaller(root)
        installer.installResources(listOf(f.archive("A", "full")))
        assertThrows(IllegalArgumentException::class.java) { installer.installResources(listOf(f.archive("C", "hotfix"))) }
        File(root, "resource/base/image/ar/a.png").writeText("XX")
        assertNull(installer.verifiedResourceContentId())
        assertThrows(IllegalArgumentException::class.java) { installer.installResources(listOf(f.archive("B", "hotfix"))) }
        assertEquals("A", installer.state().resourceVersion)
        installer.installResources(listOf(f.archive("C", "full")))
        assertTrue(installer.resourcesComplete(true))
    }

    @Test fun `unlisted payload traversal and duplicate zip entries are rejected`() {
        val f = fixture(); val root = f.root(); val installer = ProjectPackageInstaller(root)
        installer.installResources(listOf(f.archive("A", "full")))
        for (name in listOf("image/ar/unlisted.png", "../escaped")) {
            assertThrows(IllegalArgumentException::class.java) {
                installer.installResources(listOf(rewrite(f.archive("B", "hotfix"), mapOf(name to byteArrayOf(1)))))
            }
        }
        // A legal directory entry can still duplicate an earlier file's normalized path.
        val delta = f.archive("B", "hotfix")
        val duplicated = rewrite(delta, mapOf("index/ui.json/" to byteArrayOf()))
        assertThrows(IllegalArgumentException::class.java) { installer.installResources(listOf(duplicated)) }
        assertEquals("A", installer.state().resourceVersion)
        assertFalse(File(root.parentFile, "escaped").exists())
    }

    @Test fun `release descriptor and embedded manifest must agree and deletions must be exact`() {
        val f = fixture(); val root = f.root(); val installer = ProjectPackageInstaller(root)
        installer.installResources(listOf(f.archive("A", "full")))
        val delta = f.archive("B", "hotfix")
        val manifest = delta.expected!!.manifest.copy(deleted = emptyList())
        val changed = rewrite(delta, mapOf(RESOURCE_MANIFEST to Json.encodeToString(manifest).toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { installer.installResources(listOf(changed)) }
        assertThrows(IllegalArgumentException::class.java) {
            installer.installResources(listOf(changed.copy(expected = changed.expected!!.copy(manifest = manifest))))
        }
        assertEquals("A", installer.state().resourceVersion)
    }

    private fun rewrite(source: ResourceArchive, changes: Map<String, ByteArray>): ResourceArchive {
        val dest = temporary.newFile("changed-${System.nanoTime()}.zip")
        ZipFile(source.file).use { input ->
            val entries = input.entries().asSequence().associate { it.name to input.getInputStream(it).readBytes() } + changes
            ZipOutputStream(dest.outputStream()).use { out -> entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry()
            } }
        }
        return source.copy(file = dest, expected = source.expected!!.copy(size = dest.length(), sha256 = digestBytes(dest.readBytes())))
    }
}
