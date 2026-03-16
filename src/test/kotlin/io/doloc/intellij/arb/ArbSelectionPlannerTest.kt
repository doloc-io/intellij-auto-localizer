package io.doloc.intellij.arb

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ArbSelectionPlannerTest : BasePlatformTestCase() {
    private lateinit var tempDir: Path
    private val resolver = ArbPairResolver()
    private val planner = ArbSelectionPlanner(resolver)

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("arb-planner")
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

    fun testSelectedBaseIsSkippedAsTarget() {
        val scope = tempDir.resolve("feature").createDirectories()
        val base = createFile(scope.resolve("app_en.arb"), """{"@@locale":"en","hello":"Hello"}""")
        val target = createFile(scope.resolve("app_de.arb"), """{"@@locale":"de","hello":""}""")

        val result = planner.planExplicitSelection(project, listOf(base, target))
        val ready = assertIs<ArbSelectionPlanner.PlanResult.Ready>(result)
        assertEquals(1, ready.jobs.size)
        assertEquals(target.path, ready.jobs.single().targetFile.path)
    }

    fun testDifferentBasesAbortInV1() {
        val a = tempDir.resolve("a").createDirectories()
        createFile(a.resolve("app_en.arb"), """{"@@locale":"en","hello":"Hello"}""")
        val targetA = createFile(a.resolve("app_de.arb"), """{"@@locale":"de","hello":""}""")

        val b = tempDir.resolve("b").createDirectories()
        createFile(b.resolve("app_en.arb"), """{"@@locale":"en","hello":"Hello"}""")
        val targetB = createFile(b.resolve("app_fr.arb"), """{"@@locale":"fr","hello":""}""")

        val result = planner.planExplicitSelection(project, listOf(targetA, targetB))
        assertIs<ArbSelectionPlanner.PlanResult.Abort>(result)
    }

    private fun createFile(path: Path, content: String): com.intellij.openapi.vfs.VirtualFile {
        path.parent?.createDirectories()
        path.writeText(content)
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path.toFile())
            ?: error("Missing virtual file for $path")
    }
}
