package io.doloc.intellij.arb

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class ArbReminderInspector(
    private val resolver: ArbPairResolver = ArbPairResolver(),
    private val targetsFinder: ArbTranslationTargetsFinder = ArbTranslationTargetsFinder(resolver)
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
            val targets = targetsFinder.findTargetsNeedingTranslation(project, file, untranslatedRules)
            if (targets.isEmpty()) null else Reminder(ReminderType.BASE, file, targets)
        } else {
            val hasWork = targetsFinder.needsTranslation(scopeBase, file, untranslatedRules)
            if (!hasWork) null else Reminder(ReminderType.TARGET, scopeBase, listOf(file))
        }
    }
}
