package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.constant.AppPaths
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PiUpdateSourceMigrationTest {
    @get:Rule val temp = TemporaryFolder()
    @Before fun setup() = mockkObject(AppPaths)
    @After fun cleanup() = unmockkObject(AppPaths)

    private val legacy = """{"interface_version":2,"version":"v1.0.1-android.1","github":"https://github.com/Quartewe/MAH","software_github":"https://github.com/Quartewe/MAH-UIApp","project_github":"https://github.com/Quartewe/MAH","resource_github":"https://github.com/quartawa/mah_res","resource_version":"event-A","custom":{"keep":true}}"""
    private val noUnpack = object : PiPackage {
        override fun manifest(): List<String> = error("Must retain the installed project")
        override fun open(path: String) = error("Must not unpack $path")
    }

    private fun installed(metadata: String = legacy, managed: Boolean = true): File {
        val base = temp.newFolder()
        every { AppPaths.ROOT } returns base
        val root = File(base, "pi").apply { mkdirs() }
        File(base, "pi.version").writeText("347")
        File(root, "interface.json").writeText(metadata)
        if (managed) File(root, ProjectPackageInstaller.STATE).writeText(
            """{"projectVersion":"v1.0.1-android.1","resourceVersion":"event-A","revision":"retained","owners":{"resource":{"resource/base/image/a.png":"hash"}}}""",
        )
        for (name in listOf("config/config.json", "data/combat.json", "debug/log.txt", "resource/base/image/a.png", "agent/main.py")) {
            File(root, name).apply { parentFile!!.mkdirs(); writeText("keep $name") }
        }
        return root
    }

    private fun snapshot(root: File) = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }

    @Test fun `retained legacy project migrates only APK source even when version marker already matches`() {
        val root = installed()
        val before = snapshot(root)
        val installer = PiInstaller(noUnpack, 347)
        assertEquals(root, installer.ensureInstalled())
        val expected = Json.parseToJsonElement(legacy).jsonObject.toMutableMap().apply {
            put("software_github", JsonPrimitive("https://github.com/Quartewe/MAH"))
        }
        val interfaceFile = File(root, "interface.json")
        assertEquals(JsonObject(expected), Json.parseToJsonElement(interfaceFile.readText()))
        assertEquals(before - "interface.json", snapshot(root) - "interface.json")
        assertEquals("347", File(root.parentFile, "pi.version").readText())
        interfaceFile.setLastModified(1_000L)
        val modified = interfaceFile.lastModified()
        installer.ensureInstalled()
        assertEquals("Migration must be idempotent", modified, interfaceFile.lastModified())
    }

    @Test fun `custom sources unrelated projects and unmanaged bundles remain untouched`() {
        val cases = listOf(
            legacy.replace("\"software_github\":\"https://github.com/Quartewe/MAH-UIApp\"", "\"software_github\":\"https://github.com/example/custom-app\"") to true,
            legacy.replace("\"project_github\":\"https://github.com/Quartewe/MAH\"", "\"project_github\":\"https://github.com/example/other-project\"") to true,
            legacy.replace("\"software_github\":\"https://github.com/Quartewe/MAH-UIApp\"", "\"software_github\":\"https://github.com/Quartewe/MAH\"") to true,
            legacy to false,
        )
        for ((metadata, managed) in cases) {
            val root = installed(metadata, managed)
            val before = snapshot(root)
            PiInstaller(noUnpack, 347).ensureInstalled()
            assertEquals(before, snapshot(root))
        }
    }

    @Test fun `first launch of new APK migrates source while retaining an equal project and user files`() {
        val root = installed()
        val before = snapshot(root)
        val bundle = object : PiPackage {
            override fun manifest(): List<String> = error("The equal project must not be unpacked")
            override fun open(path: String) = legacy.byteInputStream()
        }
        PiInstaller(bundle, 1_000_001).ensureInstalled()
        assertEquals(before - "interface.json", snapshot(root) - "interface.json")
        assertEquals("1000001", File(root.parentFile, "pi.version").readText())
        assertEquals("https://github.com/Quartewe/MAH",
            Json.parseToJsonElement(File(root, "interface.json").readText()).jsonObject["software_github"]?.jsonPrimitive?.content)
        // Subsequent launches no longer even inspect the bundled project.
        PiInstaller(noUnpack, 1_000_001).ensureInstalled()
    }

    @Test fun `legacy repository URL accepts case trailing slash and git suffix`() {
        val root = installed(legacy.replace("https://github.com/Quartewe/MAH-UIApp", "https://github.com/quartewe/mah-uiapp.git/"))
        PiInstaller(noUnpack, 347).ensureInstalled()
        assertEquals("https://github.com/Quartewe/MAH",
            Json.parseToJsonElement(File(root, "interface.json").readText()).jsonObject["software_github"]?.jsonPrimitive?.content)
    }

    @Test fun `failed migration leaves original metadata readable and can be retried`() {
        val root = installed()
        val temporary = File(root, "interface.json.apk-source.tmp").apply { mkdirs() }
        val blocker = File(temporary, "blocker").apply { writeText("blocked") }
        val installer = PiInstaller(noUnpack, 347)
        assertThrows(java.io.IOException::class.java) { installer.ensureInstalled() }
        assertEquals(legacy, File(root, "interface.json").readText())
        assertTrue(blocker.delete())
        assertTrue(temporary.delete())
        installer.ensureInstalled()
        assertEquals("https://github.com/Quartewe/MAH",
            Json.parseToJsonElement(File(root, "interface.json").readText()).jsonObject["software_github"]?.jsonPrimitive?.content)
        assertFalse(temporary.exists())
    }
}
