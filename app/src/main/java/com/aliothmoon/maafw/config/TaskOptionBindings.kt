package com.aliothmoon.maafw.config

import com.aliothmoon.maafw.domain.*
import kotlinx.serialization.json.Json

/** UI, persistence mutations and pipeline compilation share this interpretation of binding. */
object TaskOptionBindings {
    private fun descendants(definition: ProjectDefinition, roots: List<String>): Set<String> {
        val result = linkedSetOf<String>()
        fun visit(name: String) {
            if (!result.add(name)) return
            definition.options[name]?.casesOrEmpty()?.forEach { it.childOptionNames.forEach(::visit) }
        }
        roots.forEach(::visit)
        return result
    }

    /** Targets are alternatives in the current task, never global dependencies. */
    fun targets(definition: ProjectDefinition, task: ConfiguredTask): Map<String, String> {
        val names = descendants(definition, definition.task(task.taskName)?.optionNames.orEmpty())
        return names.mapNotNull { name ->
            val candidates = definition.options[name]?.bindingTargets.orEmpty().distinct().filter { it in names }
            require(candidates.size <= 1) { "Ambiguous binding for $name: $candidates" }
            candidates.singleOrNull()?.let { target ->
                require(target !in descendants(definition, listOf(name))) { "Cyclic binding for $name" }
                require(definition.options[target] != null && definition.options[target] !is OptionDefinition.Show) { "Invalid binding target: $target" }
                name to target
            }
        }.toMap()
    }

    private fun key(definition: ProjectDefinition, target: String, values: Map<String, OptionValue>): String {
        val option = definition.options.getValue(target)
        val value = values[target] ?: when (option) {
            is OptionDefinition.Choice -> OptionValue.SingleCase(option.effectiveDefaultCase.orEmpty())
            is OptionDefinition.Checkbox -> OptionValue.MultipleCases(option.defaultCases)
            is OptionDefinition.Input -> OptionValue.Inputs(option.fields.associate { it.name to it.default })
            is OptionDefinition.Show -> error("A show option cannot be a binding target")
        }
        // Canonical keys are independent of map/set insertion order.
        val canonical = when (value) {
            is OptionValue.Inputs -> value.copy(values = value.values.toSortedMap())
            is OptionValue.MultipleCases -> value.copy(cases = value.cases.sorted())
            else -> value
        }
        return Json.encodeToString<OptionValue>(canonical)
    }

    fun effectiveValues(definition: ProjectDefinition, task: ConfiguredTask): Map<String, OptionValue> {
        var result = task.optionValues
        val targets = targets(definition, task)
        val visiting = mutableSetOf<String>()
        val done = mutableSetOf<String>()
        fun apply(source: String) {
            if (source in done) return
            require(visiting.add(source)) { "Cyclic binding: $source" }
            val target = targets.getValue(source)
            if (target in targets) apply(target)
            val state = task.bindings[source]
            if (state?.perTarget == true && state.target == target) {
                val selected = key(definition, target, result)
                result = (result - descendants(definition, listOf(source))) + state.values[selected].orEmpty()
            }
            visiting.remove(source)
            done.add(source)
        }
        targets.keys.forEach(::apply)
        return result
    }

    fun setValue(definition: ProjectDefinition, task: ConfiguredTask, name: String, value: OptionValue): ConfiguredTask {
        if (definition.options[name] is OptionDefinition.Show) return task
        val targets = targets(definition, task)
        val owner = targets.keys.filter {
            task.bindings[it]?.let { state -> state.perTarget && state.target == targets[it] } == true &&
                name in descendants(definition, listOf(it))
        }
        require(owner.size <= 1) { "Overlapping bindings for $name: $owner" }
        val source = owner.singleOrNull() ?: return task.copy(optionValues = task.optionValues + (name to value))
        val state = task.bindings.getValue(source)
        val selected = key(definition, state.target, effectiveValues(definition, task))
        return task.copy(bindings = task.bindings + (source to state.copy(
            values = state.values + (selected to (state.values[selected].orEmpty() + (name to value))),
        )))
    }

    fun setPerTarget(definition: ProjectDefinition, task: ConfiguredTask, source: String, enabled: Boolean): ConfiguredTask {
        val target = targets(definition, task)[source] ?: return task
        val old = task.bindings[source]?.takeIf { it.target == target } ?: OptionBindingState(target)
        if (old.perTarget == enabled) return task
        val effective = effectiveValues(definition, task)
        val names = descendants(definition, listOf(source))
        val selected = key(definition, target, effective)
        val snapshot = effective.filterKeys { it in names }
        val buckets = if (enabled && selected !in old.values) old.values + (selected to snapshot) else old.values
        return task.copy(
            optionValues = if (enabled) task.optionValues else (task.optionValues - names) + snapshot,
            bindings = task.bindings + (source to old.copy(perTarget = enabled, values = buckets)),
        )
    }

    fun decorate(definition: ProjectDefinition, task: ConfiguredTask, editors: List<OptionEditorState>): List<OptionEditorState> {
        val targets = targets(definition, task)
        val values = effectiveValues(definition, task)
        fun map(options: List<OptionEditorState>, inheritedScope: String = ""): List<OptionEditorState> = options.map { option ->
            val target = targets[option.name]
            val perTarget = task.bindings[option.name]?.let { it.target == target && it.perTarget } == true
            val scope = if (target == null) inheritedScope else option.name + ":" +
                if (perTarget) key(definition, target, values) else "common"
            option.copy(
                binding = targets[option.name]?.let { target -> BindingEditorState(
                    definition.options.getValue(target).label,
                    task.bindings[option.name]?.let { it.target == target && it.perTarget } == true,
                ) },
                cases = option.cases.map { it.copy(children = map(it.children, scope)) },
                editScope = scope,
            )
        }
        return map(editors)
    }
}
