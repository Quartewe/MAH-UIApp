package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.config.ConfigurationResolver
import com.aliothmoon.maafw.config.TaskOptionBindings
import com.aliothmoon.maafw.domain.*
import com.aliothmoon.maafw.runner.RunPlanBuilder
import com.aliothmoon.maafw.runner.RunPlanResult
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MahCompatibilityTest {
    private fun definition(): ProjectDefinition {
        val root = File(requireNotNull(javaClass.classLoader?.getResource("mah/interface.json")).toURI()).parentFile!!
        val result = loadWithLocale("zh-CN", DirectoryProjectSource(root)) as ProjectLoadResult.Ready
        assertTrue(result.diagnostics.toString(), result.diagnostics.none { it.severity == DiagnosticSeverity.Error })
        return result.definition
    }

    private fun configuration(task: ConfiguredTask): UserConfiguration {
        val id = RunConfigurationId("test")
        return UserConfiguration(initialized = true, configurations = listOf(RunConfiguration(id, "test", listOf(task))), activeConfigurationId = id)
    }

    @Test fun `actual MAH interface preserves five bindings and readonly progress`() {
        val definition = definition()
        assertEquals(5, definition.options.values.count { it.bindingTargets.isNotEmpty() })
        assertEquals(720, definition.controllers.single().displayShortSide)
        assertEquals(4, definition.resources.size)
        val show = definition.options.getValue("show_progress") as OptionDefinition.Show
        assertEquals("./config/config.json", show.shows.single().path)
        assertEquals("MAH.weekly_missions", show.shows.single().jsonPath)
        for (task in definition.tasks) {
            val result = RunPlanBuilder.build(definition, configuration(ConfiguredTask(task.name)))
            assertTrue("${task.name}: $result", result is RunPlanResult.Success)
        }
    }

    @Test fun `A B A binding survives restart and compiler uses the visible value`() {
        val definition = definition()
        val choices = (definition.options.getValue("choose_stage_dungeon") as OptionDefinition.Choice).cases
        val a = choices[0].name
        val b = choices[1].name
        var task = ConfiguredTask("地城作战")
        task = TaskOptionBindings.setValue(definition, task, "choose_stage_dungeon", OptionValue.SingleCase(a))
        task = TaskOptionBindings.setPerTarget(definition, task, "play_times", true)
        task = TaskOptionBindings.setValue(definition, task, "play_times", OptionValue.Inputs(mapOf("挑战次数" to "10")))
        task = TaskOptionBindings.setValue(definition, task, "choose_stage_dungeon", OptionValue.SingleCase(b))
        assertFalse(TaskOptionBindings.effectiveValues(definition, task).containsKey("play_times"))
        task = TaskOptionBindings.setValue(definition, task, "play_times", OptionValue.Inputs(mapOf("挑战次数" to "20")))
        task = TaskOptionBindings.setValue(definition, task, "choose_stage_dungeon", OptionValue.SingleCase(a))
        task = Json.decodeFromString<ConfiguredTask>(Json.encodeToString(task))
        val config = configuration(task)
        val editor = ConfigurationResolver.resolve(definition, config).activeConfiguration!!.tasks.single().options.first { it.name == "play_times" }
        assertEquals("10", editor.inputs.single().value)
        val plan = (RunPlanBuilder.build(definition, config) as RunPlanResult.Success).plan
        val patch = plan.tasks.single().pipelineOverrides.last { "Global.AutoCombat.Count" in it }
        assertEquals(10, patch.getValue("Global.AutoCombat.Count").jsonObject.getValue("action").jsonObject.getValue("param").jsonObject.getValue("custom_action_param").jsonPrimitive.int)
        val duplicate = config.configurations.single().duplicateTask(task.instanceId, "copy").tasks.last()
        val changed = TaskOptionBindings.setValue(definition, duplicate, "play_times", OptionValue.Inputs(mapOf("挑战次数" to "30")))
        assertNotEquals(task.instanceId, changed.instanceId)
        assertEquals("10", (TaskOptionBindings.effectiveValues(definition, task)["play_times"] as OptionValue.Inputs).values["挑战次数"])
        task = TaskOptionBindings.setPerTarget(definition, task, "play_times", false)
        task = TaskOptionBindings.setValue(definition, task, "choose_stage_dungeon", OptionValue.SingleCase(b))
        assertEquals("10", (TaskOptionBindings.effectiveValues(definition, task)["play_times"] as OptionValue.Inputs).values["挑战次数"])
        task = TaskOptionBindings.setPerTarget(definition, task, "play_times", true)
        assertEquals("20", (TaskOptionBindings.effectiveValues(definition, task)["play_times"] as OptionValue.Inputs).values["挑战次数"])
    }

    @Test fun `binding captures nested JSON selection and dormant children per stage`() {
        val definition = definition()
        val stages = (definition.options.getValue("choose_stage_dungeon") as OptionDefinition.Choice).cases
        val choices = (definition.options.getValue("json_load") as OptionDefinition.Choice).cases
        var task = ConfiguredTask("地城作战")
        task = TaskOptionBindings.setValue(definition, task, "choose_stage_dungeon", OptionValue.SingleCase(stages[0].name))
        task = TaskOptionBindings.setPerTarget(definition, task, "json_load", true)
        task = TaskOptionBindings.setValue(definition, task, "json_load", OptionValue.SingleCase(choices[0].name))
        task = TaskOptionBindings.setValue(definition, task, "select_formation", OptionValue.Inputs(mapOf("编队文件名" to "A.json")))
        task = TaskOptionBindings.setValue(definition, task, "choose_stage_dungeon", OptionValue.SingleCase(stages[1].name))
        task = TaskOptionBindings.setValue(definition, task, "json_load", OptionValue.SingleCase(choices[1].name))
        task = TaskOptionBindings.setValue(definition, task, "select_json", OptionValue.Inputs(mapOf("filename" to "B.json")))
        task = TaskOptionBindings.setValue(definition, task, "choose_stage_dungeon", OptionValue.SingleCase(stages[0].name))
        val values = TaskOptionBindings.effectiveValues(definition, task)
        assertEquals(OptionValue.SingleCase(choices[0].name), values["json_load"])
        assertEquals("A.json", (values["select_formation"] as OptionValue.Inputs).values["编队文件名"])
        assertFalse(values.containsKey("select_json"))
    }
}
