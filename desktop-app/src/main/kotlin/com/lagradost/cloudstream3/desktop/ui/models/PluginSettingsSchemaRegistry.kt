package com.lagradost.common.storage

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

data class PluginSettingSchema(
    val pluginPrefName: String,
    val key: String,
    val type: String,
    val defaultValue: Any?,
    val isGlobal: Boolean = false,
)

object PluginSettingsSchemaRegistry {
    val schemas = ConcurrentHashMap<String, ConcurrentHashMap<String, PluginSettingSchema>>()
    val sharedPrefNameMapping = ConcurrentHashMap<String, MutableSet<String>>()
    val schemaUpdates = MutableStateFlow(0)

    fun register(pluginPrefName: String, key: String, type: String, defaultValue: Any?, isGlobal: Boolean = false) {
        val pluginMap = schemas.getOrPut(pluginPrefName) { ConcurrentHashMap() }
        val existing = pluginMap[key]
        if (existing == null || existing.type != type) {
            pluginMap[key] = PluginSettingSchema(pluginPrefName, key, type, defaultValue, isGlobal)
            schemaUpdates.value++
        }
    }

    fun getSettingsForPlugin(pluginPrefName: String): List<PluginSettingSchema> {
        return schemas[pluginPrefName]?.values?.toList() ?: emptyList()
    }

    fun hasSettings(pluginPrefName: String): Boolean {
        return schemas.containsKey(pluginPrefName) && schemas[pluginPrefName]!!.isNotEmpty()
    }

    fun findPrefNameForPlugin(internalName: String, jarNameWithoutExt: String): String? {
        val candidates = mutableSetOf(
            "${internalName}_",
            "${jarNameWithoutExt}_",
        )

        for ((spName, pluginSet) in sharedPrefNameMapping) {
            if (internalName in pluginSet || jarNameWithoutExt in pluginSet) {
                candidates.add("${spName}_")
            }
        }

        for (candidate in candidates) {
            if (hasSettings(candidate)) return candidate
        }

        val searchTokens = setOf(internalName, jarNameWithoutExt)
        for (registeredPrefName in schemas.keys) {
            val cleanName = registeredPrefName.removeSuffix("_")
            if (searchTokens.any { token ->
                cleanName.contains(token, ignoreCase = true) || token.contains(cleanName, ignoreCase = true)
            }) {
                return registeredPrefName
            }
        }

        return null
    }

    fun recordSharedPrefMapping(prefName: String, pluginIdentifier: String) {
        sharedPrefNameMapping.getOrPut(prefName) { ConcurrentHashMap.newKeySet() }.add(pluginIdentifier)
    }

    fun removePlugin(prefName: String) {
        schemas.remove(prefName)
        schemaUpdates.value++
    }
}
