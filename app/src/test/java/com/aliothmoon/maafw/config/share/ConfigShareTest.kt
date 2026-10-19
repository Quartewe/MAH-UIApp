package com.aliothmoon.maafw.config.share

import com.aliothmoon.maafw.config.TaskOptionBindings
import com.aliothmoon.maafw.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater

/** Pure codec + existing binding consumer. No Android lifecycle, filesystem, network, or runner. */
class ConfigShareTest {
    private val empty = JsonObject(emptyMap())
    private fun case(name: String, vararg children: String) = OptionCaseDefinition(name, name, null, empty, children.toList())
    private fun field(name: String, password: Boolean = false) = InputFieldDefinition(name, PipelineType.StringType, "", null, null, null, password = password)
    private fun definition() = ProjectDefinition(
        name = "MAH", version = "v1", resources = listOf(ResourceDefinition("Official_CN", emptyList())),
        tasks = listOf(TaskDefinition("T", "Entry", description = null, groups = emptyList(), optionNames = listOf("stage", "script"),
            pipelineOverride = empty, controllers = emptyList(), resources = emptyList(), defaultCheck = true)),
        groups = emptyList(), templates = emptyList(), metadata = ProjectMetadata(resourceVersion = "res2"),
        options = mapOf(
            "stage" to OptionDefinition.Select("stage", "stage", null, listOf(case("A"), case("B")), "A"),
            "script" to OptionDefinition.Select("script", "script", null, listOf(case("file", "file"), case("manual", "manual")), "file", bindingTargets = listOf("stage")),
            "file" to OptionDefinition.Input("file", "file", null, listOf(field("path"), field("token", true)), empty),
            "manual" to OptionDefinition.Input("manual", "manual", null, listOf(field("value"), field("legacy")), empty),
            "outside" to OptionDefinition.Select("outside", "outside", null, listOf(case("x")), "x"),
        ),
    )

    private fun options() = Json.parseToJsonElement("""
        {"stage":[
          {"value":"A","script":{"value":"file","branches":{
            "file":{"file":{"value":{"path":"a.json","token":"plaintext-A"}}},
            "manual":{"manual":{"value":{"value":"visible-A","legacy":"plain-hidden"},"encrypted_values":{"legacy":"ciphertext-A"}}}}}},
          {"value":"B","script":{"value":"file","branches":{
            "file":{"file":{"value":{"path":"b.json","token":"gAAAA-example-cipher"}}},
            "manual":{"manual":{"value":{"value":"visible-B","legacy":"plain-hidden"},"sealed":{"legacy":"ciphertext-B"}}}}}}
        ],"_binding_active":{"stage":"A"},"_speedrun_config":{"enabled":false,"mode":"daily"}}
    """).jsonObject

    private fun compress(text: String, raw: Boolean = false): String {
        val deflater = Deflater(9, raw)
        val output = ByteArrayOutputStream()
        try {
            deflater.setInput(text.encodeToByteArray()); deflater.finish()
            val buffer = ByteArray(4096)
            while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer))
        } finally { deflater.end() }
        return Base64.getUrlEncoder().encodeToString(output.toByteArray())
    }

    private fun code(options: JsonObject, bundle: String = "MAH", taskSource: String? = null) = "MFWCFG1:" + compress(buildJsonObject {
        put("v", 1); put("bundle", bundle); put("resource_version", "res2"); put("resource_name", "Official_CN")
        put("name", "Example")
        putJsonArray("tasks") { add(buildJsonObject {
            put("name", "T"); put("is_checked", false); put("task_option", options)
            taskSource?.let { put("task_source", it) }
        }) }
    }.toString())

    private fun path(definition: ProjectDefinition, task: ConfiguredTask) =
        (TaskOptionBindings.effectiveValues(definition, task).getValue("file") as OptionValue.Inputs).values.getValue("path")

    @Test fun `different child values in binding buckets remain independently editable`() {
        val definition = definition()
        val imported = ConfigShare.decode(code(options()), definition)
        var task = imported.configurations.single().tasks.single()
        assertFalse(task.enabled)
        assertEquals("a.json", path(definition, task))
        assertEquals(2, task.bindings.getValue("script").values.size)
        assertTrue(task.bindings.getValue("script").perTarget)
        task = TaskOptionBindings.setValue(definition, task, "stage", OptionValue.SingleCase("B"))
        assertEquals("b.json", path(definition, task))
        task = TaskOptionBindings.setValue(definition, task, "file", OptionValue.Inputs(mapOf("path" to "edited-b.json", "token" to "new-secret")))
        val configuration = imported.configurations.single().copy(tasks = listOf(task))
        val decoded = ConfigShare.decode(ConfigShare.encode(configuration, definition, "Official_CN"), definition)
        val roundTrip = decoded.configurations.single().tasks.single()
        assertEquals("edited-b.json", path(definition, roundTrip))
        assertEquals("a.json", path(definition, TaskOptionBindings.setValue(definition, roundTrip, "stage", OptionValue.SingleCase("A"))))
        assertEquals(JsonPrimitive(false), roundTrip.sharedOptions?.get("_speedrun_config")?.jsonObject?.get("enabled"))
        assertTrue(decoded.versionWarnings.isEmpty())
    }

    @Test fun `secrets are removed from active inactive and persisted raw branches`() {
        val definition = definition()
        val imported = ConfigShare.decode(code(options()), definition).configurations.single()
        val raw = imported.tasks.single().sharedOptions.toString()
        listOf("plaintext", "gAAAA", "ciphertext", "plain-hidden", "sealed", "encrypted_values").forEach { assertFalse(raw.contains(it)) }
        imported.tasks.single().bindings.getValue("script").values.values.forEach { values ->
            assertEquals("", (values.getValue("file") as OptionValue.Inputs).values["token"])
            assertEquals("", (values.getValue("manual") as OptionValue.Inputs).values["legacy"])
        }
    }

    @Test fun `legacy bare input flat children and grouped branches are accepted`() {
        val tree = Json.parseToJsonElement("""{"script":{"value":"file","children":{"file":{"path":"old.json","token":"secret"}},"branches":{"manual":{"manual":{"value":{"value":"m"}}}}}}""").jsonObject
        val imported = ConfigShare.decode(code(tree), definition()).configurations.single().tasks.single()
        assertEquals("old.json", (imported.optionValues["file"] as OptionValue.Inputs).values["path"])
        assertEquals("m", (imported.optionValues["manual"] as OptionValue.Inputs).values["value"])
    }

    @Test fun `conflicting representations and active speedrun are reported`() {
        val conflicts = Json.parseToJsonElement("""{"script":{"value":"file","children":{"file":{"value":{"path":"a"}}},"branches":{"file":{"file":{"value":{"path":"b"}}}}}}""").jsonObject
        assertThrows(IllegalArgumentException::class.java) { ConfigShare.decode(code(conflicts), definition()) }
        val activeSpeedrun = JsonObject(options() + ("_speedrun_config" to buildJsonObject { put("enabled", true) }))
        assertThrows(IllegalArgumentException::class.java) { ConfigShare.decode(code(activeSpeedrun), definition()) }
        assertThrows(IllegalArgumentException::class.java) { ConfigShare.decode(code(empty, taskSource = "plugin"), definition()) }
    }

    @Test fun `same child name with distinct branch values is explicitly reported`() {
        val original = definition()
        val repeated = (original.options.getValue("script") as OptionDefinition.Select).copy(cases = listOf(case("file", "file"), case("manual", "file")))
        val definition = original.copy(options = original.options + ("script" to repeated))
        val tree = Json.parseToJsonElement("""{"script":{"value":"file","branches":{"file":{"file":{"value":{"path":"a"}}},"manual":{"file":{"value":{"path":"b"}}}}}}""").jsonObject
        val error = assertThrows(IllegalArgumentException::class.java) { ConfigShare.decode(code(tree), definition) }
        assertTrue(error.message.orEmpty().contains("repeated child option: file"))
    }

    @Test fun `resource identity aliases require an explicit match`() {
        assertThrows(IllegalArgumentException::class.java) { ConfigShare.decode(code(empty, "local-bundle"), definition()) }
        assertEquals(1, ConfigShare.decode(code(empty, "local-bundle"), definition(), bundleAliases = setOf("local-bundle")).configurations.size)
    }

    @Test fun `MXU raw deflate short keys preserve controller enabled state and discard encrypted inputs`() {
        val wire = """{"cn":"Android","rn":"Official_CN","t":[{"tn":"T","e":false,"ec":{"Android":true},"ov":{"file":{"t":"in","v":{"path":"mxu.json","token":"old-secret"},"ev":{"token":"sealed-secret"}}}}]}"""
        val share = "Heading\nMAH://tab-sharing/v1/A%2BB/" + compress(wire, raw = true) + "\nFooter"
        val imported = ConfigShare.decode(share, definition()).configurations.single()
        assertEquals("A+B", imported.name)
        assertTrue(imported.tasks.single().enabled)
        val input = imported.tasks.single().optionValues.getValue("file") as OptionValue.Inputs
        assertEquals("mxu.json", input.values["path"])
        assertEquals("", input.values["token"])
    }

    @Test fun `MXU files reject options outside task and unsupported hotkeys`() {
        listOf(""""outside":{"type":"select","caseName":"x"}""", """"file":{"type":"hotkey","value":"F5"}""").forEach { option ->
            val text = """{"instances":[{"name":"test","tasks":[{"taskName":"T","optionValues":{$option}}]}]}"""
            assertThrows(RuntimeException::class.java) { ConfigShare.decode(text, definition(), "mxu-MAH.json") }
        }
    }

    @Test fun `missing active bucket and duplicate target entries are reported`() {
        val missing = JsonObject(options() + ("_binding_active" to buildJsonObject { put("stage", "absent") }))
        assertThrows(IllegalArgumentException::class.java) { ConfigShare.decode(code(missing), definition()) }
        val entries = options().getValue("stage").jsonArray
        val duplicate = JsonObject(options() + ("stage" to JsonArray(entries + entries.first())))
        assertThrows(IllegalArgumentException::class.java) { ConfigShare.decode(code(duplicate), definition()) }
    }
}
