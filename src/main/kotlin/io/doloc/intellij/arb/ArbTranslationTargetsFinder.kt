package io.doloc.intellij.arb

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class ArbTranslationTargetsFinder(
    private val resolver: ArbPairResolver = ArbPairResolver(),
    private val analyzer: ArbFileAnalyzer = ArbFileAnalyzer()
) {
    fun findTargetsNeedingTranslation(
        project: Project,
        baseFile: VirtualFile,
        untranslatedRules: Set<String>
    ): List<VirtualFile> {
        return resolver.inferTargetFiles(project, baseFile)
            .filter { targetFile -> needsTranslation(baseFile, targetFile, untranslatedRules) }
    }

    fun needsTranslation(
        baseFile: VirtualFile,
        targetFile: VirtualFile,
        untranslatedRules: Set<String>
    ): Boolean {
        return analyzer.analyze(baseFile, targetFile, untranslatedRules).hasUntranslatedKeys
    }
}
