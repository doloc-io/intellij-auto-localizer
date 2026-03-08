package io.doloc.intellij.arb

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ArbReminderInspectorTest : BasePlatformTestCase() {
    private val inspector = ArbReminderInspector()
    private lateinit var tempDir: Path

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("arb-reminder")
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

    fun testTargetReminderUsesResolvedBase() {
        val scope = tempDir.resolve("feature").createDirectories()
        val base = createFile(scope.resolve("app_en.arb"), """{"@@locale":"en","hello":"Hello","bye":"Bye"}""")
        val target = createFile(scope.resolve("app_de.arb"), """{"@@locale":"de","hello":"Hallo"}""")

        val reminder = inspector.inspect(project, target, setOf("missing"))
        assertNotNull(reminder)
        assertEquals(ArbReminderInspector.ReminderType.TARGET, reminder.type)
        assertEquals(base.path, reminder.baseFile.path)
    }

    fun testBaseReminderCollectsTargets() {
        val scope = tempDir.resolve("feature").createDirectories()
        val base = createFile(scope.resolve("app_en.arb"), """{"@@locale":"en","hello":"Hello","bye":"Bye"}""")
        createFile(scope.resolve("app_de.arb"), """{"@@locale":"de","hello":"Hallo"}""")
        createFile(scope.resolve("app_fr.arb"), """{"@@locale":"fr","hello":"Bonjour"}""")

        val reminder = inspector.inspect(project, base, setOf("missing"))
        assertNotNull(reminder)
        assertEquals(ArbReminderInspector.ReminderType.BASE, reminder.type)
        assertEquals(2, reminder.targetFiles.size)
    }

    private fun createFile(path: Path, content: String): com.intellij.openapi.vfs.VirtualFile {
        path.parent?.createDirectories()
        path.writeText(content)
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path.toFile())
            ?: error("Missing virtual file for $path")
    }
}
