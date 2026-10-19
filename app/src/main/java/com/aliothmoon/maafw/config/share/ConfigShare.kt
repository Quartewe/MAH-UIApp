package com.aliothmoon.maafw.config.share

import com.aliothmoon.maafw.config.TaskOptionBindings
import com.aliothmoon.maafw.domain.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Public MFW v1 envelope. Device settings and secrets never enter this representation. */
object ConfigShare {
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAGIC = "MFWCFG1:"
    private val json = Json { ignoreUnknownKeys = true }

    data class Import(val configurations: List<RunConfiguration>, val versionWarnings: List<String>, val resourceName: String?)

    private fun version(definition: ProjectDefinition) = definition.metadata.resourceVersion ?: definition.version.orEmpty()

    fun encode(configuration: RunConfiguration, definition: ProjectDefinition, resourceName: String?): String {
        val payload = buildJsonObject {
            put("v", 1); put("bundle", definition.name); put("resource_version", version(definition))
            put("name", configuration.name); put("resource_name", resourceName ?: definition.resources.firstOrNull()?.name.orEmpty())
            putJsonArray("tasks") {
                configuration.tasks.forEach { task -> add(buildJsonObject {
                    require(definition.task(task.taskName) != null) { "Unavailable task: ${task.taskName}" }
                    put("name", task.taskName); put("is_checked", task.enabled)
                    put("task_option", exportOptions(task, definition))
                }) }
            }
        }.toString().encodeToByteArray()
        require(payload.size <= MAX_BYTES) { "Configuration exceeds 4 MiB" }
        val deflater = Deflater(9)
        val out = ByteArrayOutputStream()
        try {
            deflater.setInput(payload); deflater.finish()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        } finally { deflater.end() }
        return MAGIC + Base64.getUrlEncoder().encodeToString(out.toByteArray())
    }

    fun decode(text: String, definition: ProjectDefinition, fileName: String? = null, bundleAliases: Set<String> = emptySet()): Import {
        require(text.toByteArray().size <= MAX_BYTES) { "Configuration exceeds 4 MiB" }
        val trimmed = text.trim().removePrefix("\uFEFF")
        if (!trimmed.startsWith(MAGIC)) return decodeMxu(trimmed, definition, fileName, bundleAliases)
        val root = json.parseToJsonElement(inflate(Base64.getUrlDecoder().decode(trimmed.removePrefix(MAGIC)))).jsonObject
        require(root["v"]?.jsonPrimitive?.let { !it.isString && it.intOrNull == 1 } == true) { "Unsupported MFW share version" }
        require(root.string("bundle") in bundleAliases + definition.name) { "Resource mismatch: ${root.string("bundle")} / ${definition.name}" }
        val resource = root.string("resource_name")?.takeIf(String::isNotBlank)
        require(resource == null || definition.resources.any { it.name == resource }) { "Resource selection unavailable: $resource" }
        val tasks = requireNotNull(root["tasks"] as? JsonArray) { "Invalid task list" }.map { element ->
            val entry = element.jsonObject
            val name = requireNotNull(entry.string("name")) { "Missing task name" }
            require(entry.string("task_source").let { it == null || it == "resource" } && entry.string("builtin_key").isNullOrEmpty()) { "Unsupported task: $name" }
            val enabled = entry["is_checked"]?.jsonPrimitive?.takeUnless { it.isString }?.booleanOrNull ?: if ("is_checked" !in entry) true else error("Invalid task enabled state")
            importOptions(name, enabled, entry["task_option"]?.jsonObject ?: JsonObject(emptyMap()), definition)
        }
        val sharedVersion = root.string("resource_version").orEmpty()
        return Import(listOf(RunConfiguration(RunConfigurationId(UUID.randomUUID().toString()), root.string("name") ?: "Imported configuration", tasks)),
            if (sharedVersion != version(definition)) listOf("${sharedVersion.ifBlank { "?" }} → ${version(definition).ifBlank { "?" }}") else emptyList(), resource)
    }

    private fun inflate(bytes: ByteArray): String {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                require(n > 0 || inflater.finished()) { "Incomplete share code" }
                out.write(buffer, 0, n)
                require(out.size() <= MAX_BYTES) { "Configuration exceeds 4 MiB" }
            }
            require(inflater.remaining == 0) { "Trailing share code data" }
            return out.toByteArray().decodeToString(throwOnInvalidSequence = true)
        } finally { inflater.end() }
    }

    private fun defaultValue(option: OptionDefinition): OptionValue? = when (option) {
        is OptionDefinition.Choice -> OptionValue.SingleCase(option.effectiveDefaultCase.orEmpty())
        is OptionDefinition.Checkbox -> OptionValue.MultipleCases(option.defaultCases)
        is OptionDefinition.Input -> OptionValue.Inputs(option.fields.associate { it.name to if (it.password) "" else it.default }, option.fields.filter { it.password }.map { it.name }.toSet())
        is OptionDefinition.Show -> null
    }

    private fun valueFromJson(option: OptionDefinition, value: JsonElement?): OptionValue? {
        if (value == null || value is JsonNull) return defaultValue(option)
        return when (option) {
            is OptionDefinition.Choice -> {
                val selected = value.jsonPrimitive.content
                require(option.cases.any { it.name == selected }) { "Unavailable case: ${option.name}" }
                OptionValue.SingleCase(selected)
            }
            is OptionDefinition.Checkbox -> {
                val selected = value.jsonArray.map { it.jsonPrimitive.content }
                require(selected.distinct().size == selected.size && selected.all { name -> option.cases.any { it.name == name } } && option.acceptsCount(selected.size)) { "Unavailable cases: ${option.name}" }
                OptionValue.MultipleCases(selected)
            }
            is OptionDefinition.Input -> {
                val values = value.jsonObject
                require(values.keys.all { key -> option.fields.any { it.name == key } }) { "Unavailable input field: ${option.name}" }
                OptionValue.Inputs(option.fields.associate { field -> field.name to if (field.password) "" else (values[field.name]?.jsonPrimitive?.content ?: field.default) },
                    option.fields.filter { it.password }.map { it.name }.toSet())
            }
            is OptionDefinition.Show -> null
        }
    }

    private fun valueJson(value: OptionValue): JsonElement = when (value) {
        is OptionValue.SingleCase -> JsonPrimitive(value.case)
        is OptionValue.MultipleCases -> JsonArray(value.cases.map(::JsonPrimitive))
        is OptionValue.Inputs -> JsonObject(value.values.mapValues { (name, content) -> JsonPrimitive(if (name in value.secretFields || name in value.sealed) "" else content) })
    }

    private fun canonical(value: OptionValue): String = Json.encodeToString<OptionValue>(when (value) {
        is OptionValue.Inputs -> value.copy(values = value.values.toSortedMap())
        is OptionValue.MultipleCases -> value.copy(cases = value.cases.sorted())
        else -> value
    })

    private fun children(option: OptionDefinition): Map<String, Pair<String, String>> = buildMap {
        option.casesOrEmpty().forEach { case -> case.childOptionNames.forEachIndexed { index, child ->
            put("${option.name}_child_${case.name}_${child}_$index", child to case.name)
        } }
    }

    private fun payloadObject(option: OptionDefinition, payload: JsonElement): JsonObject {
        val obj = payload.jsonObject
        if (option is OptionDefinition.Input && "value" !in obj && obj.keys.all { key -> key == "hidden" || option.fields.any { it.name == key } }) {
            return buildJsonObject {
                put("value", JsonObject(obj.filterKeys { it != "hidden" }))
                obj["hidden"]?.let { put("hidden", it) }
            }
        }
        return obj
    }

    /** Old flat children, compound keys, and upstream case-grouped branches share one schema. */
    private fun branchEntries(option: OptionDefinition, obj: JsonObject): Map<String, Pair<String, JsonElement>> {
        val declared = children(option)
        val result = linkedMapOf<String, Pair<String, JsonElement>>()
        fun add(key: String, payload: JsonElement, case: String? = null) {
            val matches = declared.filter { (wire, child) -> (case == null || child.second == case) && (key == wire || key == child.first) }
            require(matches.isNotEmpty()) { "Unavailable child option: $key" }
            matches.forEach { (wire, child) ->
                require(result[wire]?.second.let { it == null || it == payload }) { "Conflicting children/branches: $wire" }
                result[wire] = child.first to payload
            }
        }
        listOf("children", "branches").forEach { field ->
            val entries = obj[field]?.jsonObject.orEmpty()
            entries.forEach { (key, payload) ->
                val groupKeys = declared.filterValues { it.second == key }.flatMap { (wire, child) -> listOf(wire, child.first) }.toSet()
                val isFlat = key in declared || declared.values.any { it.first == key }
                val grouped = groupKeys.isNotEmpty() && (!isFlat || payload is JsonObject && payload.isNotEmpty() && payload.keys.all { it in groupKeys })
                if (grouped) payload.jsonObject.forEach { (child, nested) -> add(child, nested, key) }
                else add(key, payload)
            }
        }
        return result
    }

    private fun markedFields(obj: JsonObject): Set<String> = buildSet {
        listOf("secretFields", "secret_fields").forEach { field ->
            (obj[field] as? JsonArray).orEmpty().forEach { add(it.jsonPrimitive.content) }
        }
        listOf("sealed", "encryptedValues", "encrypted_values").forEach { field -> addAll((obj[field] as? JsonObject).orEmpty().keys) }
        (obj["value"] as? JsonObject).orEmpty().forEach { (field, value) ->
            if (value is JsonObject && value.keys.any { it in setOf("__secret__", "ciphertext", "encrypted") }) add(field)
        }
    }

    private fun importOptions(name: String, enabled: Boolean, tree: JsonObject, definition: ProjectDefinition): ConfiguredTask {
        val taskDefinition = requireNotNull(definition.task(name)) { "Unavailable task: $name" }
        require(taskDefinition.controllers.isEmpty() || definition.controllers.any { it.name in taskDefinition.controllers }) { "Unsupported controller for task: $name" }
        val seed = ConfiguredTask(name, enabled)
        val targets = TaskOptionBindings.targets(definition, seed)
        val common = linkedMapOf<String, OptionValue>()
        val bindings = linkedMapOf<String, OptionBindingState>()
        require("_binding_active" !in tree || tree["_binding_active"] is JsonObject) { "Invalid active binding map" }
        fun collect(optionName: String, payload: JsonElement, into: MutableMap<String, OptionValue>, path: Set<String> = emptySet()) {
            require(optionName !in path) { "Cyclic option: $optionName" }
            val option = requireNotNull(definition.options[optionName]) { "Unavailable option: $optionName" }
            val obj = payloadObject(option, payload)
            val marked = markedFields(obj)
            val rawValue = if (option is OptionDefinition.Input && obj["value"] is JsonObject) {
                JsonObject(obj.getValue("value").jsonObject.mapValues { (field, value) -> if (field in marked) JsonPrimitive("") else value })
            } else obj["value"]
            var value = valueFromJson(option, rawValue)
            if (value is OptionValue.Inputs) {
                val secrets = value.secretFields + marked
                value = value.copy(values = value.values.mapValues { (key, v) -> if (key in secrets) "" else v }, secretFields = secrets)
            }
            if (value != null) {
                val old = into.put(optionName, value)
                require(old == null || old == value) { "Different values for repeated child option: $optionName" }
            }
            branchEntries(option, obj).values.forEach { (childName, childPayload) ->
                collect(childName, childPayload, into, path + optionName)
            }
            val reserved = setOf("value", "children", "branches", "hidden", "secretFields", "secret_fields", "sealed", "encryptedValues", "encrypted_values")
            require(obj.keys.all { it in reserved || targets[it] == optionName }) { "Unsupported option data: $optionName" }
        }
        tree.forEach { (optionName, payload) ->
            if (optionName == "_binding_active") return@forEach
            if (optionName == "_speedrun_config") {
                val speedrun = payload.jsonObject
                require(speedrun["enabled"]?.jsonPrimitive?.takeUnless { it.isString }?.booleanOrNull == false) { "Speedrun rules require manual setup: $name" }
                return@forEach
            }
            require(optionName in taskDefinition.optionNames) { "Unavailable root option: $optionName" }
            if (payload is JsonArray) {
                require(optionName in targets.values && payload.isNotEmpty()) { "Unsupported option array: $optionName" }
                val option = definition.options.getValue(optionName)
                require(option !is OptionDefinition.Input || option.fields.none { it.password }) { "Password cannot be a binding key: $optionName" }
                val activeValue = (tree["_binding_active"] as? JsonObject)?.get(optionName)
                val active = if (activeValue == null) payload.first() else requireNotNull(payload.firstOrNull { it.jsonObject["value"] == activeValue }) { "Active binding value is missing: $optionName" }
                collect(optionName, active, common)
                val sources = targets.filterValues { it == optionName }.keys
                sources.forEach { source ->
                    val buckets = linkedMapOf<String, Map<String, OptionValue>>()
                    payload.forEach { entry ->
                        val obj = entry.jsonObject
                        require(markedFields(obj).isEmpty()) { "Password cannot be a binding key: $optionName" }
                        val key = canonical(requireNotNull(valueFromJson(option, obj["value"])))
                        require(key !in buckets) { "Duplicate binding key: $optionName" }
                        val values = linkedMapOf<String, OptionValue>()
                        obj[source]?.let { collect(source, it, values) }
                        buckets[key] = values
                        // A target's children are common in Android; refuse data that cannot be represented.
                        val targetValues = linkedMapOf<String, OptionValue>()
                        collect(optionName, entry, targetValues)
                        targetValues.filterKeys { it != optionName }.forEach { (keyName, childValue) ->
                            require(common[keyName] == childValue) { "Different target children per binding: $keyName" }
                        }
                    }
                    bindings[source] = OptionBindingState(optionName, source !in tree, buckets)
                }
            } else collect(optionName, payload, common)
        }
        (tree["_binding_active"] as? JsonObject).orEmpty().keys.forEach { target ->
            require(target in targets.values && tree[target] is JsonArray) { "Unavailable active binding: $target" }
        }
        val native = seed.copy(optionValues = common, bindings = bindings)
        // Re-rendering performs schema-driven redaction in all branches and buckets before persistence.
        return native.copy(sharedOptions = exportOptions(native.copy(sharedOptions = tree), definition))
    }

    private fun exportOptions(task: ConfiguredTask, definition: ProjectDefinition): JsonObject {
        val roots = requireNotNull(definition.task(task.taskName)).optionNames
        val targets = TaskOptionBindings.targets(definition, task)
        val original = task.sharedOptions.orEmpty()
        fun build(name: String, values: Map<String, OptionValue>, previous: JsonElement? = null, path: Set<String> = emptySet()): JsonObject {
            require(name !in path) { "Cyclic option: $name" }
            val option = requireNotNull(definition.options[name]) { "Unavailable option: $name" }
            val old = previous as? JsonObject
            val value = values[name] ?: defaultValue(option)
            val safeValue = if (value is OptionValue.Inputs && option is OptionDefinition.Input) value.copy(secretFields = value.secretFields + option.fields.filter { it.password }.map { it.name }) else value
            return buildJsonObject {
                if (safeValue != null) put("value", valueJson(safeValue))
                val childEntries = children(option)
                if (childEntries.isNotEmpty()) putJsonObject("children") {
                    val priorChildren = (old?.get("branches") ?: old?.get("children")) as? JsonObject
                    childEntries.forEach { (key, child) ->
                        val nested = build(child.first, values, priorChildren?.get(key), path + name).toMutableMap()
                        val selected = when (safeValue) { is OptionValue.SingleCase -> listOf(safeValue.case); is OptionValue.MultipleCases -> safeValue.cases; else -> emptyList() }
                        if (child.second !in selected) nested["hidden"] = JsonPrimitive(true)
                        put(key, JsonObject(nested))
                    }
                }
            }
        }
        val result = linkedMapOf<String, JsonElement>()
        roots.forEach { name -> result[name] = build(name, task.optionValues, original[name]) }
        val active = linkedMapOf<String, JsonElement>()
        targets.values.distinct().forEach { target ->
            val option = definition.options.getValue(target)
            val sources = targets.filterValues { it == target }.keys
            val oldEntries = (original[target] as? JsonArray).orEmpty().associateBy { canonical(requireNotNull(valueFromJson(option, it.jsonObject["value"]))) }
            val current = task.optionValues[target] ?: requireNotNull(defaultValue(option))
            val keys = linkedSetOf<String>().apply {
                addAll(oldEntries.keys)
                sources.forEach { source -> addAll(task.bindings[source]?.values.orEmpty().keys) }
                add(canonical(current))
            }
            result[target] = JsonArray(keys.map { key ->
                val targetValue = Json.decodeFromString<OptionValue>(key)
                val entry = build(target, task.optionValues + (target to targetValue), oldEntries[key]).toMutableMap()
                sources.forEach { source ->
                    val state = task.bindings[source]
                    val bucket = state?.values?.get(key)
                    if (bucket != null || state?.perTarget == true) entry[source] = build(source, bucket.orEmpty(), oldEntries[key]?.jsonObject?.get(source))
                }
                JsonObject(entry)
            })
            active[target] = valueJson(current)
            sources.filter { task.bindings[it]?.perTarget == true }.forEach(result::remove)
        }
        if (active.isNotEmpty()) result["_binding_active"] = JsonObject(active)
        // Inactive speedrun metadata survives a desktop round trip; enabled rules are refused at import.
        original["_speedrun_config"]?.let {
            require(it.jsonObject["enabled"]?.jsonPrimitive?.takeUnless { value -> value.isString }?.booleanOrNull == false) { "Speedrun rules require manual setup: ${task.taskName}" }
            result["_speedrun_config"] = it
        }
        return JsonObject(result)
    }

    private fun decodeMxu(text: String, definition: ProjectDefinition, fileName: String?, bundleAliases: Set<String>): Import {
        val instances: List<MxuInstance>
        if (MxuShareCodes.looksLikeShareCode(text)) {
            val decoded = MxuShareCodes.decode(text)
            require(decoded is MxuShareCodeResult.Decoded) { "Invalid or unsupported MXU share code" }
            require(decoded.code.projectName in bundleAliases + definition.name) { "Resource mismatch: ${decoded.code.projectName} / ${definition.name}" }
            instances = listOf(decoded.code.instance)
        } else {
            require((bundleAliases + definition.name).any { MxuFormat.mismatchedProjectName(fileName, it) == null }) { "MXU resource mismatch" }
            val root = MxuFormat.decodeConfig(text)
            require(root.globalOptionValues.isEmpty()) { "MXU global settings require manual setup" }
            instances = root.instances
        }
        require(instances.isNotEmpty()) { "No configurations found" }
        val resourceNames = instances.mapNotNull { it.resourceName }.distinct()
        require(resourceNames.size <= 1 && resourceNames.all { r -> definition.resources.any { it.name == r } }) { "MXU resource selection requires manual setup" }
        val configurations = instances.map { instance ->
            require(instance.preActions.isEmpty() && instance.preAction == null && instance.schedulePolicies.isEmpty()) { "MXU pre-actions and schedules require manual setup" }
            RunConfiguration(RunConfigurationId(UUID.randomUUID().toString()), instance.name.ifBlank { "Imported configuration" }, instance.tasks.map { task ->
                require(definition.task(task.taskName) != null) { "Unavailable task: ${task.taskName}" }
                val values = task.optionValues.mapValues { (name, element) ->
                    val option = requireNotNull(definition.options[name]) { "Unavailable option: $name" }
                    val converted = when (val value = MxuOptionValue.parse(element)) {
                        is MxuOptionValue.Select -> JsonPrimitive(value.caseName)
                        is MxuOptionValue.Checkbox -> JsonArray(value.caseNames.map(::JsonPrimitive))
                        is MxuOptionValue.Switch -> {
                            val cases = (option as? OptionDefinition.Choice)?.cases.orEmpty()
                            require(cases.size == 2) { "Invalid switch: $name" }
                            JsonPrimitive(cases[if (value.value) 0 else 1].name)
                        }
                        is MxuOptionValue.Input -> JsonObject((value.values + value.encryptedValues.mapValues { "" }).mapValues { (_, v) -> JsonPrimitive(v) })
                        else -> error("Unsupported MXU option: $name")
                    }
                    requireNotNull(valueFromJson(option, converted)) { "Read-only option: $name" }
                }
                val native = ConfiguredTask(task.taskName, task.enabledByController?.get(instance.controllerName) ?: task.enabled, values, task.customName)
                // Verify roots/children and execution support using the same MFW conversion.
                val tree = exportOptions(native, definition)
                val imported = importOptions(native.taskName, native.enabled, tree, definition)
                require(values.keys.all { it in imported.optionValues }) { "MXU option outside task: ${task.taskName}" }
                imported.copy(customLabel = task.customName)
            })
        }
        return Import(configurations, listOf("MXU: ? → ${version(definition).ifBlank { "?" }}"), resourceNames.singleOrNull())
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
