package io.doloc.intellij.arb

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class ArbSelectionPlanner(
    private val resolver: ArbPairResolver
) {
    data class PromptResolution(
        val jobs: List<ArbTranslationJob>,
        val selectedBaseFile: VirtualFile? = null
    )

    sealed class PlanResult {
        data class Ready(
            val jobs: List<ArbTranslationJob>,
            val confirmationTargets: List<VirtualFile> = emptyList()
        ) : PlanResult()

        data class PromptRequired(
            val scopeDir: VirtualFile,
            val resolutions: List<ArbPairResolver.ArbTargetResolution>,
            val confirmationTargets: List<VirtualFile> = emptyList()
        ) : PlanResult()

        data class Abort(
            val title: String,
            val message: String
        ) : PlanResult()
    }

    fun planExplicitSelection(project: Project, files: List<VirtualFile>): PlanResult {
        val resolutions = files.map { resolver.resolveTarget(project, it) }
        val targetResolutions = resolutions.filterNot { resolution ->
            resolution.baseFile != null && resolution.targetFile.path == resolution.baseFile.path
        }

        if (targetResolutions.isEmpty()) {
            return PlanResult.Abort(
                "ARB Translation",
                "The selected ARB files resolve only to base files. Select one base file by itself to translate all inferred targets, or select explicit target files."
            )
        }

        val baseGroups = targetResolutions.mapNotNull { it.baseFile?.path }.distinct()
        if (baseGroups.size > 1) {
            return PlanResult.Abort(
                "ARB Translation",
                buildMultiBaseMessage(targetResolutions)
            )
        }

        val scopeGroups = targetResolutions.map { it.scope.scopeDir.path }.distinct()
        if (scopeGroups.size > 1) {
            return PlanResult.Abort(
                "ARB Translation",
                "The selected ARB files belong to different base scopes. Please translate one base group at a time in v1."
            )
        }

        val needsPrompt = targetResolutions.any {
            it.needsBasePrompt || it.needsSourceLangPrompt || it.needsTargetLangPrompt
        }
        if (needsPrompt) {
            return PlanResult.PromptRequired(
                scopeDir = targetResolutions.first().scope.scopeDir,
                resolutions = targetResolutions
            )
        }

        return PlanResult.Ready(targetResolutions.mapNotNull(::toJob))
    }

    fun planBaseSelection(
        project: Project,
        baseFile: VirtualFile,
        targetsOverride: List<VirtualFile>? = null
    ): PlanResult {
        val targets = targetsOverride ?: resolver.inferTargetFiles(project, baseFile)
        if (targets.isEmpty()) {
            return PlanResult.Abort(
                "ARB Translation",
                "No target ARB files could be inferred for ${baseFile.name}."
            )
        }

        val resolutions = targets.map { resolver.resolveTarget(project, it) }
        val needsPrompt = resolutions.any {
            it.needsBasePrompt || it.needsSourceLangPrompt || it.needsTargetLangPrompt
        }
        if (needsPrompt) {
            return PlanResult.PromptRequired(
                scopeDir = resolutions.first().scope.scopeDir,
                resolutions = resolutions,
                confirmationTargets = targets
            )
        }

        return PlanResult.Ready(
            jobs = resolutions.mapNotNull(::toJob),
            confirmationTargets = targets
        )
    }

    fun resolvePromptResult(plan: PlanResult.PromptRequired, result: ArbResolutionDialog.Result): PromptResolution {
        val jobs = plan.resolutions.mapNotNull { resolution ->
            if (resolution.targetFile.path == result.baseFile.path) {
                return@mapNotNull null
            }
            ArbTranslationJob(
                scopeDir = resolution.scope.scopeDir,
                baseFile = result.baseFile,
                targetFile = resolution.targetFile,
                sourceLang = result.sourceLang,
                targetLang = result.targetLangs[resolution.targetFile.path]
                    ?: resolution.targetLang
                    ?: return@mapNotNull null
            )
        }

        val selectedBaseFile = result.baseFile.takeIf {
            jobs.isEmpty() &&
                plan.resolutions.size == 1 &&
                plan.resolutions.single().targetFile.path == result.baseFile.path
        }

        return PromptResolution(jobs = jobs, selectedBaseFile = selectedBaseFile)
    }

    private fun toJob(resolution: ArbPairResolver.ArbTargetResolution): ArbTranslationJob? {
        val baseFile = resolution.baseFile ?: return null
        val sourceLang = resolution.sourceLang?.takeIf { it.isNotBlank() } ?: return null
        val targetLang = resolution.targetLang?.takeIf { it.isNotBlank() } ?: return null
        return ArbTranslationJob(
            scopeDir = resolution.scope.scopeDir,
            baseFile = baseFile,
            targetFile = resolution.targetFile,
            sourceLang = sourceLang,
            targetLang = targetLang
        )
    }

    private fun buildMultiBaseMessage(resolutions: List<ArbPairResolver.ArbTargetResolution>): String {
        val grouped = resolutions.groupBy { it.baseFile?.name ?: "<unresolved base>" }
        return buildString {
            append("The selected ARB files resolve to different base files. Please translate one base group at a time in v1.\n\n")
            grouped.entries.sortedBy { it.key }.forEach { (baseName, group) ->
                append(baseName)
                append(':')
                append('\n')
                group.sortedBy { it.targetFile.name }.forEach { resolution ->
                    append("- ")
                    append(resolution.targetFile.name)
                    append('\n')
                }
                append('\n')
            }
        }.trim()
    }
}
