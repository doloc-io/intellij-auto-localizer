package io.doloc.intellij.arb

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArbPairResolverTest : BasePlatformTestCase() {
    private val resolver = ArbPairResolver()
    private lateinit var tempDir: Path

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("arb-resolver")
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

    fun testSameScopeSavedOverrideBeatsL10nYaml() {
        val featureDir = tempDir.resolve("feature").createDirectories()
        val l10nDir = featureDir.resolve("lib/l10n").createDirectories()
        featureDir.resolve("l10n.yaml").writeText(
            """
            arb-dir: lib/l10n
            template-arb-file: app_en.arb
            """.trimIndent()
        )
        val template = createFile(l10nDir.resolve("app_en.arb"), """{"@@locale":"en","hello":"Hello"}""")
        val customBase = createFile(l10nDir.resolve("custom_en.arb"), """{"@@locale":"en","hello":"Hello"}""")
        val target = createFile(l10nDir.resolve("app_de.arb"), """{"@@locale":"de","hello":""}""")

        ArbProjectOverridesService.getInstance(project).saveScopeOverride(featureDir.toVirtualFile(), customBase, "en")

        val resolution = resolver.resolveTarget(project, target)
        assertEquals(customBase.path, resolution.baseFile?.path)
        assertEquals("en", resolution.sourceLang)
        assertEquals("de", resolution.targetLang)
        assertTrue(template.path != resolution.baseFile?.path)
    }

    fun testNearerL10nYamlBeatsFartherSavedOverride() {
        val rootBaseDir = tempDir.resolve("rootL10n").createDirectories()
        val rootBase = createFile(rootBaseDir.resolve("root_en.arb"), """{"@@locale":"en","hello":"Hello"}""")
        ArbProjectOverridesService.getInstance(project).saveScopeOverride(tempDir.toVirtualFile(), rootBase, "en")

        val moduleDir = tempDir.resolve("packages/demo").createDirectories()
        val moduleL10n = moduleDir.resolve("lib/l10n").createDirectories()
        moduleDir.resolve("l10n.yaml").writeText(
            """
            arb-dir: lib/l10n
            template-arb-file: app_en.arb
            """.trimIndent()
        )
        val moduleBase = createFile(moduleL10n.resolve("app_en.arb"), """{"@@locale":"en","hello":"Hello"}""")
        val target = createFile(moduleL10n.resolve("app_fr.arb"), """{"@@locale":"fr","hello":""}""")

        val resolution = resolver.resolveTarget(project, target)
        assertEquals(moduleBase.path, resolution.baseFile?.path)
    }

    fun testDirectoryLocaleLayoutResolvesEnglishSiblingAsBase() {
        val scope = tempDir.resolve("feature").createDirectories()
        val enDir = scope.resolve("en").createDirectories()
        val deDir = scope.resolve("de").createDirectories()
        val base = createFile(enDir.resolve("app.arb"), """{"@@locale":"en","hello":"Hello"}""")
        val target = createFile(deDir.resolve("app.arb"), """{"@@locale":"de","hello":""}""")

        val resolution = resolver.resolveTarget(project, target)
        assertEquals(base.path, resolution.baseFile?.path)
        assertTrue(resolver.isScopeBase(project, base))
    }

    fun testAmbiguousCandidatesRequirePrompt() {
        val scope = tempDir.resolve("feature").createDirectories()
        val base = createFile(scope.resolve("en/app.arb"), """{"@@locale":"en","hello":"Hello"}""")
        val secondarySource = createFile(scope.resolve("en-US/app.arb"), """{"@@locale":"en-US","hello":"Hello"}""")
        val target = createFile(scope.resolve("de/app.arb"), """{"@@locale":"de","hello":""}""")

        val resolution = resolver.resolveTarget(project, target)
        assertNull(resolution.baseFile)
        assertTrue(resolution.needsBasePrompt)
        assertEquals(2, resolution.baseCandidates.size)

        val inferredTargets = resolver.inferTargetFiles(project, base)
        assertTrue(target.path in inferredTargets.map { it.path })
        assertTrue(secondarySource.path !in inferredTargets.map { it.path })
    }

    private fun createFile(path: Path, content: String): com.intellij.openapi.vfs.VirtualFile {
        path.parent?.createDirectories()
        path.writeText(content)
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path.toFile())
            ?: error("Missing virtual file for $path")
    }

    private fun Path.toVirtualFile() = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(this.toFile())
        ?: error("Missing virtual file for $this")
}
