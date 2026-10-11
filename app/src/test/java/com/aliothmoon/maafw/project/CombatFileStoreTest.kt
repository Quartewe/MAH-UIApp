package com.aliothmoon.maafw.project

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CombatFileStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val combat = """{
        "team": {"LEADER":{"name":"sanzo","id":2},"SUPPORT":{"name":"kyouma","id":2}},
        "fight": {"pos":{"1":[0,0]},"action":{"0":{"char":1,"action":["U"]}}},
        "description": "花本编队", "future": {"value":12345678901234567890}
    }"""

    @Test fun `import and export preserve original content and team order`() {
        val root = temp.newFolder()
        val store = CombatFileStore(root)
        val imported = store.importFile("flower.json", combat.byteInputStream())
        assertEquals("flower.json", imported.name)
        assertEquals(combat, File(root, "data/auto_combat/flower.json").readText())
        assertArrayEquals(combat.toByteArray(), store.exportFile(imported.relativePath))
        assertEquals(listOf(imported), store.list())
    }

    @Test fun `nested duplicate names get a unique filename without replacing originals`() {
        val root = temp.newFolder()
        val existing = File(root, "data/auto_combat/default/default.json")
        existing.parentFile.mkdirs()
        existing.writeText(combat)
        File(root, "data/auto_combat/default_2.json").writeText(combat)
        val store = CombatFileStore(root)
        val imported = store.importFile("default.json", combat.byteInputStream())
        assertEquals("default_3.json", imported.name)
        assertEquals(3, store.list().size)
        assertEquals(combat, existing.readText())
        assertArrayEquals(combat.toByteArray(), store.exportFile("default/default.json"))
    }

    @Test fun `invalid documents and filenames leave the directory unchanged`() {
        val root = temp.newFolder()
        val store = CombatFileStore(root)
        listOf("{}", "[]", "{", """{"team":{},"fight":[]}""", """{"tasks":[]}""").forEach { text ->
            assertThrows(IllegalArgumentException::class.java) {
                store.importFile("bad.json", text.byteInputStream())
            }
        }
        listOf("../outside.json", "dir/file.json", "C:\\a.json", "a*.json", "a[1].json", "a.txt").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { store.importFile(name, combat.byteInputStream()) }
        }
        assertTrue(store.list().isEmpty())
        assertFalse(File(root, "data/auto_combat").exists())
    }

    @Test fun `BOM is removed so Python utf8 reader can load the imported file`() {
        val store = CombatFileStore(temp.newFolder())
        val entry = store.importFile("flower.JSON", ("\uFEFF" + combat).byteInputStream())
        assertEquals("flower.json", entry.name)
        assertEquals(combat, store.exportFile(entry.relativePath).decodeToString())
    }

    @Test fun `oversized input and export traversal are rejected`() {
        val store = CombatFileStore(temp.newFolder())
        assertThrows(IllegalArgumentException::class.java) {
            store.importFile("big.json", ByteArray(CombatFileStore.MAX_BYTES + 1).inputStream())
        }
        assertThrows(IllegalArgumentException::class.java) { store.exportFile("../private.json") }
        assertThrows(IllegalArgumentException::class.java) { store.exportFile("C:/private.json") }
        assertTrue(store.list().isEmpty())
    }
}
