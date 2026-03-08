package io.doloc.intellij.arb

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class ArbReminderInspector(
    private val resolver: ArbPairResolver = ArbPairResolver(),
    private val analyzer: ArbFileAnalyzer = ArbFileAnalyzer()
) {
    enum class ReminderType {
        TARGET,
        BASE
    }

    data class Reminder(
        val type: ReminderType,
        val baseFile: VirtualFile,
        val targetFiles: List<VirtualFile>
    )

    fun inspect(project: Project, file: VirtualFile, untranslatedRules: Set<String>): Reminder? {
        val scopeBase = resolver.resolveScopeBase(project, file) ?: return null
        return if (scopeBase.path == file.path) {
            val targets = resolver.inferTargetFiles(project, file)
                .filter { analyzer.analyze(file, it, untranslatedRules).hasUntranslatedKeys }
            if (targets.isEmpty()) null else Reminder(ReminderType.BASE, file, targets)
        } else {
            val hasWork = analyzer.analyze(scopeBase, file, untranslatedRules).hasUntranslatedKeys
            if (!hasWork) null else Reminder(ReminderType.TARGET, scopeBase, listOf(file))
        }
    }
}
