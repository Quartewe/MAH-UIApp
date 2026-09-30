package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.ShowDefinition
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShowContentReaderTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `refresh reads latest nested progress and cannot leave the project`() {
        val root = temp.newFolder()
        val file = File(root, "config.json")
        val show = ShowDefinition("Progress", "./config.json", "MAH.weekly_missions", "auto")
        assertEquals("", ShowContentReader.read(root, show))
        file.writeText("""{"MAH":{"weekly_missions":{"done":1}},"private":"hidden"}""")
        val initial = ShowContentReader.read(root, show)
        assertTrue(initial.contains("1")); assertFalse(initial.contains("private"))
        file.writeText("""{"MAH":{"weekly_missions":{"done":2}}}""")
        assertTrue(ShowContentReader.read(root, show).contains("2"))
        assertThrows(IllegalArgumentException::class.java) { ShowContentReader.read(root, show.copy(path = "../outside")) }
    }
}
