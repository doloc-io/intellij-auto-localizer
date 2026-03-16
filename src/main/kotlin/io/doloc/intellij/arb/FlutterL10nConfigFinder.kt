package io.doloc.intellij.arb

import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

class FlutterL10nConfigFinder {
    data class FlutterL10nConfig(
        val configDir: VirtualFile,
        val arbDirPath: String,
        val arbDir: VirtualFile?,
        val templateArbFile: String
    )

    fun findNearest(startDirectory: VirtualFile?): FlutterL10nConfig? {
        var current = startDirectory
        while (current != null) {
            val configFile = current.findChild("l10n.yaml")
            if (configFile != null && !configFile.isDirectory) {
                return parseConfig(current, VfsUtil.loadText(configFile))
            }
            current = current.parent
        }
        return null
    }

    fun parseConfig(configDir: VirtualFile, content: String): FlutterL10nConfig {
        var arbDirPath = "lib/l10n"
        var templateArbFile = "app_en.arb"

        content.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) {
                return@forEach
            }

            val colonIndex = trimmed.indexOf(':')
            if (colonIndex <= 0) {
                return@forEach
            }

            val key = trimmed.substring(0, colonIndex).trim()
            val value = trimmed.substring(colonIndex + 1)
                .substringBefore(" #")
                .trim()
                .trim('"', '\'')

            when (key) {
                "arb-dir" -> if (value.isNotBlank()) arbDirPath = value
                "template-arb-file" -> if (value.isNotBlank()) templateArbFile = value
            }
        }

        return FlutterL10nConfig(
            configDir = configDir,
            arbDirPath = arbDirPath,
            arbDir = resolveRelativeDir(configDir, arbDirPath),
            templateArbFile = templateArbFile
        )
    }

    private fun resolveRelativeDir(configDir: VirtualFile, relativePath: String): VirtualFile? {
        val parts = relativePath.split('/').filter { it.isNotBlank() && it != "." }
        return if (parts.isEmpty()) configDir else configDir.findFileByRelativePath(parts.joinToString("/"))
    }
}
