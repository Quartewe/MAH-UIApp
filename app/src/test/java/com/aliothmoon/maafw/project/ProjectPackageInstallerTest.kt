package com.aliothmoon.maafw.project

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectPackageInstallerTest {
    @get:Rule val temp = TemporaryFolder()
    private fun zip(entries: Map<String, String>): File = temp.newFile().also { archive ->
        ZipOutputStream(archive.outputStream()).use { out -> entries.forEach { (name, value) ->
            out.putNextEntry(ZipEntry(name)); out.write(value.toByteArray()); out.closeEntry()
        } }
    }
    private fun root(): File = temp.newFolder("pi").also { dir ->
        File(dir, "interface.json").writeText("""{"interface_version":2,"name":"test","resource":[{"name":"base","path":["resource/base"]}]}""")
        File(dir, "config").mkdirs()
        File(dir, "config/config.json").writeText("user progress")
        File(dir, "data").mkdirs()
        File(dir, "data/custom.json").writeText("user combat")
        File(dir, "resource/base/image").mkdirs()
        File(dir, "resource/base/image/owned.png").writeText("own UI")
    }
    private val full = mapOf("image/character/a.png" to "A", "image/ar/b.png" to "B",
        "index/ui.json" to "{}", "index/characters.json" to "{}", "index/ar.json" to "{}")

    @Test fun `full resources replace only files owned by the resource channel`() {
        val dir = root()
        val installer = ProjectPackageInstaller(dir)
        installer.install(zip(full + ("image/ar/old.png" to "old")), ProjectPackageTarget.Resource, "activity-A")
        val first = installer.state().revision
        installer.install(zip(full), ProjectPackageTarget.Resource, "activity-B")
        assertFalse(File(dir, "resource/base/image/ar/old.png").exists())
        assertTrue(File(dir, "resource/base/image/owned.png").isFile)
        assertEquals("user progress", File(dir, "config/config.json").readText())
        assertEquals("user combat", File(dir, "data/custom.json").readText())
        assertFalse(File(dir, "image").exists())
        assertFalse(File(dir, "index").exists())
        assertEquals("activity-B", installer.state().resourceVersion)
        assertNotEquals(first, installer.state().revision)
    }

    @Test fun `invalid package never advances installed version or erases the previous files`() {
        val dir = root()
        val installer = ProjectPackageInstaller(dir)
        installer.install(zip(full), ProjectPackageTarget.Resource, "A")
        assertThrows(IllegalArgumentException::class.java) {
            installer.install(zip(full + ("../escape" to "bad")), ProjectPackageTarget.Resource, "B")
        }
        assertEquals("A", installer.state().resourceVersion)
        assertEquals("A", File(dir, "resource/base/image/character/a.png").readText())
        assertFalse(File(dir.parentFile, "escape").exists())
    }

    @Test fun `interrupted directory swap restores last committed project at startup`() {
        val dir = root()
        val work = File(dir.parentFile, ".pi-update").also { it.mkdirs() }
        assertTrue(dir.renameTo(File(work, "backup")))
        dir.mkdirs()
        File(dir, "interface.json").writeText("partial")
        ProjectPackageInstaller(dir).recover()
        assertEquals("user progress", File(dir, "config/config.json").readText())
        assertFalse(work.exists())
    }

    @Test fun `project scripts update independently of resources and custom user data`() {
        val dir = root()
        val installer = ProjectPackageInstaller(dir)
        installer.install(zip(full), ProjectPackageTarget.Resource, "event-A")
        val content = mapOf(
            "interface.json" to """{"interface_version":2,"version":"v2","resource":[{"name":"base","path":["resource/base"]}]}""",
            "agent/main.py" to "# project v2",
            "data/custom.json" to "new default",
        )
        val manifest = buildJsonObject {
            put("format", 1); put("target", "project"); put("version", "v2")
            put("framework", "v5.14.2"); put("agentCore", "3.13.15-maafw5.14.2")
            putJsonObject("files") { content.forEach { (name, text) ->
                put(name, java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) })
            } }
        }.toString()
        installer.install(zip(content + ("mah-package.json" to manifest)), ProjectPackageTarget.Project, "v2")
        assertEquals("# project v2", File(dir, "agent/main.py").readText())
        assertEquals("user combat", File(dir, "data/custom.json").readText())
        assertEquals("event-A", installer.state().resourceVersion)
        assertEquals("v2", installer.state().projectVersion)
        assertEquals("A", File(dir, "resource/base/image/character/a.png").readText())
        assertThrows(IllegalArgumentException::class.java) {
            installer.install(zip(content + ("agent/main.py" to "corrupt") + ("mah-package.json" to manifest)), ProjectPackageTarget.Project, "v2")
        }
        assertEquals("# project v2", File(dir, "agent/main.py").readText())
    }
}
