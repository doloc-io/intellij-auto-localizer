package io.doloc.intellij.arb

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ArbProjectOverridesServiceTest : BasePlatformTestCase() {
    private lateinit var tempDir: Path

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("arb-overrides")
        ArbProjectOverridesService.getInstance(project).replaceScopes(emptyList())
    }

    override fun tearDown() {
        try {
            ArbProjectOverridesService.getInstance(project).replaceScopes(emptyList())
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    @Test
    fun testStateSerializesWithoutProjectReference() {
        val service = ArbProjectOverridesService.getInstance(project)
        service.replaceScopes(
            listOf(
                ArbProjectOverridesService.ArbScopeOverride(
                    scopeDir = "feature",
                    baseFile = "lib/l10n/app_en.arb",
                    sourceLang = "en",
                    targetOverrides = mutableListOf(
                        ArbProjectOverridesService.ArbTargetOverride(
                            targetFile = "lib/l10n/app_de.arb",
                            targetLang = "de"
                        )
                    )
                )
            )
        )

        val serialized = XmlSerializer.serialize(service.state)

        assertNotNull(serialized)
    }

    @Test
    fun testResolveStoredDirectoryReturnsDirectoriesOnly() {
        val service = ArbProjectOverridesService.getInstance(project)
        val scopeDir = tempDir.resolve("feature/l10n").createDirectories()
        val arbFile = tempDir.resolve("feature/l10n/app_en.arb").toFile().apply {
            parentFile.mkdirs()
            writeText("{}")
        }

        val resolvedDirectory = service.resolveStoredDirectory(scopeDir.toVirtualFile().path)

        assertNotNull(resolvedDirectory)
        assertNull(service.resolveStoredDirectory(arbFile.path))
        assertNull(service.resolveStoredFile(scopeDir.toString()))
    }

    private fun Path.toVirtualFile() = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(toFile())
        ?: error("Missing virtual file for $this")
}
