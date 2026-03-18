package io.doloc.intellij.api

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

object DolocRequestMetadata {
    private const val PLUGIN_ID = "io.doloc.auto-localizer"
    private const val USER_AGENT_PRODUCT = "doloc-intellij-plugin"
    const val VERSION_HEADER_NAME = "X-intellij-plugin-version"

    private val pluginVersion: String by lazy {
        resolvePluginVersion()
    }

    fun pluginVersion(): String = pluginVersion

    fun userAgent(): String = "$USER_AGENT_PRODUCT/$pluginVersion"

    private fun resolvePluginVersion(): String {
        PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return loadVersionFromPluginXml() ?: "unknown"
    }

    private fun loadVersionFromPluginXml(): String? {
        val stream = DolocRequestMetadata::class.java.getResourceAsStream("/META-INF/plugin.xml") ?: return null
        return stream.bufferedReader().use { reader ->
            versionTagPattern.find(reader.readText())?.groupValues?.getOrNull(1)
        }
    }

    private val versionTagPattern = Regex("<version>\\s*([^<]+?)\\s*</version>")
}
