package io.doloc.intellij.arb

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArbTranslationWorkflowTest : BasePlatformTestCase() {
    private lateinit var tempDir: Path

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("arb-workflow")
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

    fun testExplicitTranslationShowsErrorAndAbortsWhenTargetArbIsMalformed() {
        val scope = tempDir.resolve("feature").createDirectories()
        createFile(scope.resolve("app_en.arb"), """{"@@locale":"en","hello":"Hello"}""")
        val target = createFile(scope.resolve("app_de.arb"), """{"@@locale":"de","hello": UNQUOTED VALUE }""")

        val parseErrors = mutableListOf<String>()
        var overwriteConfirmed = false
        var jobsExecuted = false

        val workflow = createWorkflow(
            onConfirmOverwrite = {
                overwriteConfirmed = true
                true
            },
            onExecuteJobs = { jobsExecuted = true },
            onParseError = { parseErrors += it }
        )

        workflow.performTranslation(project, listOf(target))

        assertFalse(overwriteConfirmed)
        assertFalse(jobsExecuted)
        assertEquals(1, parseErrors.size)
        assertTrue(parseErrors.single().contains("app_de.arb"))
    }

    fun testBaseTranslationShowsErrorAndAbortsWhenTargetArbIsMalformed() {
        val scope = tempDir.resolve("feature").createDirectories()
        val base = createFile(scope.resolve("app_en.arb"), """{"@@locale":"en","hello":"Hello"}""")
        createFile(scope.resolve("app_de.arb"), """{"@@locale":"de","hello": UNQUOTED VALUE }""")

        val parseErrors = mutableListOf<String>()
        var fanOutConfirmed = false
        var overwriteConfirmed = false
        var jobsExecuted = false

        val workflow = createWorkflow(
            onConfirmOverwrite = {
                overwriteConfirmed = true
                true
            },
            onConfirmFanOut = {
                fanOutConfirmed = true
                true
            },
            onExecuteJobs = { jobsExecuted = true },
            onParseError = { parseErrors += it }
        )

        workflow.performBaseTranslation(project, base)

        assertFalse(fanOutConfirmed)
        assertFalse(overwriteConfirmed)
        assertFalse(jobsExecuted)
        assertEquals(1, parseErrors.size)
        assertTrue(parseErrors.single().contains("app_de.arb"))
    }

    fun testBaseTranslationSkipsFanOutConfirmationWhenRequested() {
        val scope = tempDir.resolve("feature").createDirectories()
        val base = createFile(scope.resolve("app_en.arb"), """{"@@locale":"en","hello":"Hello"}""")
        createFile(scope.resolve("app_de.arb"), """{"@@locale":"de"}""")

        var fanOutConfirmed = false
        var overwriteConfirmed = false
        var jobsExecuted = false

        val workflow = createWorkflow(
            onConfirmOverwrite = {
                overwriteConfirmed = true
                true
            },
            onConfirmFanOut = {
                fanOutConfirmed = true
                true
            },
            onExecuteJobs = { jobsExecuted = true }
        )

        workflow.performBaseTranslation(project, base, skipFanOutConfirmation = true)

        assertFalse(fanOutConfirmed)
        assertTrue(overwriteConfirmed)
        assertTrue(jobsExecuted)
    }

    private fun createWorkflow(
        onConfirmOverwrite: () -> Boolean = { true },
        onConfirmFanOut: () -> Boolean = { true },
        onExecuteJobs: () -> Unit = {},
        onParseError: (String) -> Unit = {}
    ): ArbTranslationWorkflow {
        return ArbTranslationWorkflow(
            pairResolver = ArbPairResolver(),
            targetsFinder = ArbTranslationTargetsFinder(),
            ensureApiToken = { true },
            confirmOverwriteTargets = { _, _ -> onConfirmOverwrite() },
            confirmFanOut = { _, _, _ -> onConfirmFanOut() },
            executeJobs = { _, _, _ -> onExecuteJobs() },
            showParseError = { _, message, _ -> onParseError(message) }
        )
    }

    private fun createFile(path: Path, content: String): com.intellij.openapi.vfs.VirtualFile {
        path.parent?.createDirectories()
        path.writeText(content)
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path.toFile())
            ?: error("Missing virtual file for $path")
    }
}
