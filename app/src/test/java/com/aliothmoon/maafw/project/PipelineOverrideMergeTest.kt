package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.*
import com.aliothmoon.maafw.runner.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PipelineOverrideMergeTest {
    private fun definition(vararg patches: String): ProjectDefinition {
        val options = patches.mapIndexed { i, patch ->
            """"p$i":{"type":"select","cases":[{"name":"on","pipeline_override":$patch}]}"""
        }.joinToString(",")
        val names = patches.indices.joinToString(",") { "\"p$it\"" }
        val pi = """{"interface_version":2,"name":"merge-test",
            "resource":[{"name":"r","path":"resource"}],
            "controller":[{"name":"c","type":"Adb"}],
            "task":[{"name":"t","entry":"N","option":[$names]}],"option":{$options}}"""
        return (loadWithLocale("zh-CN", MapProjectSource(mapOf("interface.json" to pi))) as ProjectLoadResult.Ready).definition
    }

    private fun plan(definition: ProjectDefinition, tasks: List<ConfiguredTask> = listOf(ConfiguredTask("t"))): RunPlan {
        val id = RunConfigurationId("merge")
        val config = UserConfiguration(initialized = true, activeConfigurationId = id,
            configurations = listOf(RunConfiguration(id, "merge", tasks)))
        val result = RunPlanBuilder.build(definition, config)
        assertTrue(result.toString(), result is RunPlanResult.Success)
        return (result as RunPlanResult.Success).plan
    }

    private fun RuntimeTask.node(): JsonObject = pipelineOverrides.last { "N" in it }.getValue("N").jsonObject

    @Test fun `nested objects retain disjoint fields and later values win without mutating inputs`() {
        val definition = definition(
            """{"N":{"action":{"param":{"custom_action_param":{"name":"sanzo","id":2,"nested":{"keep":1}}}}}}""",
            """{"N":{"action":{"param":{"custom_action_param":{"id":3,"Level":70,"nested":{"new":2}}}}}}""",
        )
        val before = definition.options.toString()
        val node = plan(definition).tasks.single().node()
        val params = node["action"]!!.jsonObject["param"]!!.jsonObject["custom_action_param"]!!.jsonObject
        assertEquals(Json.parseToJsonElement("""{"name":"sanzo","id":3,"Level":70,"nested":{"keep":1,"new":2}}"""), params)
        assertEquals(before, definition.options.toString())
        assertEquals(node, plan(definition).tasks.single().node())
    }

    @Test fun `arrays scalars null and object type switches replace rather than concatenate`() {
        val definition = definition(
            """{"N":{"next":["old"],"enabled":true,"focus":{"old":1},"value":{"old":1},"param":"default.json"}}""",
            """{"N":{"next":[],"enabled":false,"focus":null,"value":"file.json","param":{"name":"sanzo"}}}""",
            """{"N":{"value":{"new":2}}}""",
        )
        assertEquals(Json.parseToJsonElement("""{"next":[],"enabled":false,"focus":null,"value":{"new":2},"param":{"name":"sanzo"}}"""),
            plan(definition).tasks.single().node())
    }

    @Test fun `base global resource controller task priority preserves lower priority fields`() {
        val base = definition(
            """{"N":{"value":"global","global":1}}""",
            """{"N":{"value":"resource","resource":2}}""",
            """{"N":{"value":"controller","controller":3}}""",
            """{"N":{"value":"task","task":4}}""",
        )
        val scoped = base.copy(globalOptionNames = listOf("p0"),
            resources = base.resources.map { it.copy(optionNames = listOf("p1")) },
            controllers = base.controllers.map { it.copy(optionNames = listOf("p2")) },
            tasks = base.tasks.map { it.copy(optionNames = listOf("p3"), pipelineOverride =
                Json.parseToJsonElement("""{"N":{"value":"base","base":0}}""").jsonObject) })
        assertEquals(Json.parseToJsonElement("""{"value":"task","base":0,"global":1,"resource":2,"controller":3,"task":4}"""),
            plan(scoped).tasks.single().node())
    }

    @Test fun `separate tasks do not share merged fields`() {
        val base = definition("""{"N":{"first":1}}""", """{"N":{"second":2}}""")
        val definition = base.copy(tasks = listOf(base.tasks.single().copy(optionNames = listOf("p0")),
            base.tasks.single().copy(name = "other", optionNames = listOf("p1"))))
        val tasks = plan(definition, listOf(ConfiguredTask("t"), ConfiguredTask("other"))).tasks
        assertEquals(Json.parseToJsonElement("""{"first":1}"""), tasks[0].node())
        assertEquals(Json.parseToJsonElement("""{"second":2}"""), tasks[1].node())
    }
}
