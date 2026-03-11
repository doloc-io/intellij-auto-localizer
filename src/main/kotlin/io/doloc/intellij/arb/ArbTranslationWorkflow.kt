package io.doloc.intellij.arb

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import io.doloc.intellij.settings.DolocSettingsState

class ArbTranslationWorkflow(
    private val pairResolver: ArbPairResolver,
    private val targetsFinder: ArbTranslationTargetsFinder,
    private val ensureApiToken: (Project) -> Boolean,
    private val confirmOverwriteTargets: (Project, List<VirtualFile>) -> Boolean,
    private val confirmFanOut: (Project, VirtualFile, List<VirtualFile>) -> Boolean,
    private val executeJobs: (Project, List<ArbTranslationJob>, String) -> Unit,
    private val showParseError: (Project, String, String) -> Unit = { project, message, title ->
        Messages.showErrorDialog(project, message, title)
    }
) {
    fun performTranslation(project: Project, files: List<VirtualFile>) {
        if (!ensureApiToken(project)) return

        FileDocumentManager.getInstance().saveAllDocuments()

        val planner = ArbSelectionPlanner(pairResolver)
        val jobs = try {
            val plan = planner.planExplicitSelection(project, files)
            resolvePlan(project, planner, plan)
        } catch (e: ArbParseException) {
            handleParseFailure(project, e)
            return
        } ?: return
        if (jobs.isEmpty()) return

        if (!confirmOverwriteTargets(project, jobs.map { it.targetFile })) {
            return
        }

        executeJobs(project, jobs, "Translating ARB files")
    }

    fun performBaseTranslation(project: Project, baseFile: VirtualFile) {
        if (!ensureApiToken(project)) return

        FileDocumentManager.getInstance().saveAllDocuments()

        val filteredTargets = try {
            targetsFinder.findTargetsNeedingTranslation(
                project,
                baseFile,
                DolocSettingsState.getInstance().arbUntranslatedStates
            )
        } catch (e: ArbParseException) {
            handleParseFailure(project, e)
            return
        }

        if (filteredTargets.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "No target ARB files need translation for ${baseFile.name}.",
                "Auto Localizer"
            )
            return
        }

        val planner = ArbSelectionPlanner(pairResolver)
        val jobs = try {
            val plan = planner.planBaseSelection(project, baseFile, filteredTargets)
            resolvePlan(project, planner, plan)
        } catch (e: ArbParseException) {
            handleParseFailure(project, e)
            return
        } ?: return
        if (jobs.isEmpty()) return

        if (!confirmFanOut(project, baseFile, filteredTargets)) {
            return
        }

        if (!confirmOverwriteTargets(project, jobs.map { it.targetFile })) {
            return
        }

        executeJobs(project, jobs, "Translating ${baseFile.name} to target ARB files")
    }

    private fun resolvePlan(
        project: Project,
        planner: ArbSelectionPlanner,
        plan: ArbSelectionPlanner.PlanResult
    ): List<ArbTranslationJob>? {
        val confirmationTargets = when (plan) {
            is ArbSelectionPlanner.PlanResult.Ready -> plan.confirmationTargets
            is ArbSelectionPlanner.PlanResult.PromptRequired -> plan.confirmationTargets
            is ArbSelectionPlanner.PlanResult.Abort -> emptyList()
        }

        return when (plan) {
            is ArbSelectionPlanner.PlanResult.Abort -> {
                Messages.showInfoMessage(project, plan.message, plan.title)
                null
            }

            is ArbSelectionPlanner.PlanResult.Ready -> plan.jobs

            is ArbSelectionPlanner.PlanResult.PromptRequired -> {
                val dialog = ArbResolutionDialog(project, plan.scopeDir, plan.resolutions)
                if (!dialog.showAndGet()) {
                    null
                } else {
                    val result = dialog.result ?: return null
                    val promptResolution = planner.resolvePromptResult(plan, result)
                    if (promptResolution.selectedBaseFile != null) {
                        if (result.rememberScope) {
                            ArbProjectOverridesService.getInstance(project).saveScopeOverride(
                                plan.scopeDir,
                                result.baseFile,
                                result.sourceLang
                            )
                        }
                        performBaseTranslation(project, promptResolution.selectedBaseFile)
                        return null
                    }
                    persistPromptOverrides(project, plan.scopeDir, result, promptResolution.jobs)
                    promptResolution.jobs
                }
            }
        }?.also {
            if (confirmationTargets.isNotEmpty() && it.isEmpty()) {
                Messages.showInfoMessage(project, "No ARB target files need translation.", "Auto Localizer")
            }
        }
    }

    private fun persistPromptOverrides(
        project: Project,
        scopeDir: VirtualFile,
        result: ArbResolutionDialog.Result,
        jobs: List<ArbTranslationJob>
    ) {
        val overrides = ArbProjectOverridesService.getInstance(project)
        if (result.rememberScope) {
            overrides.saveScopeOverride(scopeDir, result.baseFile, result.sourceLang)
        }
        jobs.forEach { job ->
            if (job.targetFile.path in result.rememberedTargets) {
                overrides.saveTargetLanguage(job.scopeDir, job.targetFile, job.targetLang)
            }
        }
    }

    private fun handleParseFailure(project: Project, exception: ArbParseException) {
        showParseError(project, exception.toUserMessage(), "ARB Translation")
    }
}
