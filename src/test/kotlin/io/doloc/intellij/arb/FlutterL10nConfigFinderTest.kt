package io.doloc.intellij.arb

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class FlutterL10nConfigFinderTest : BasePlatformTestCase() {
    private val finder = FlutterL10nConfigFinder()

    fun testFindNearestConfigInSubdirectory() {
        val root = Files.createTempDirectory("flutter-l10n")
        val moduleDir = Files.createDirectories(root.resolve("apps/demo"))
        Files.createDirectories(moduleDir.resolve("lib/l10n"))
        moduleDir.resolve("l10n.yaml").writeText(
            """
            arb-dir: lib/l10n
            template-arb-file: app_en.arb
            """.trimIndent()
        )
        moduleDir.resolve("lib/l10n/app_en.arb").writeText("{}")
        moduleDir.resolve("lib/l10n/app_de.arb").writeText("{}")

        val targetFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(moduleDir.resolve("lib/l10n/app_de.arb").toFile())
            ?: error("Expected target ARB file")

        val config = finder.findNearest(targetFile.parent) ?: error("Expected l10n config")
        assertEquals("lib/l10n", config.arbDirPath)
        assertEquals("app_en.arb", config.templateArbFile)
        assertEquals("app_en.arb", config.arbDir?.findChild(config.templateArbFile)?.name)
    }
}
