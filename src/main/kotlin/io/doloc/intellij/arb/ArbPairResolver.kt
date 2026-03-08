package io.doloc.intellij.arb

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class ArbPairResolver(
    private val configFinder: FlutterL10nConfigFinder = FlutterL10nConfigFinder()
) {
    private val analyzer = ArbFileAnalyzer()

    enum class BaseResolutionOrigin {
        SAVED_OVERRIDE,
        L10N_CONFIG,
        HEURISTIC,
        NONE
    }

    data class ArbResolutionScope(
        val scopeDir: VirtualFile,
        val searchRoot: VirtualFile,
        val savedOverride: ArbProjectOverridesService.ArbScopeOverride?,
        val l10nConfig: FlutterL10nConfigFinder.FlutterL10nConfig?
    )

    data class ArbTargetResolution(
        val scope: ArbResolutionScope,
        val targetFile: VirtualFile,
        val baseCandidates: List<VirtualFile>,
        val baseFile: VirtualFile?,
        val baseOrigin: BaseResolutionOrigin,
        val sourceLang: String?,
        val targetLang: String?,
        val needsBasePrompt: Boolean,
        val needsSourceLangPrompt: Boolean,
        val needsTargetLangPrompt: Boolean
    )

    fun resolveScope(project: Project, file: VirtualFile): ArbResolutionScope {
        val overrides = ArbProjectOverridesService.getInstance(project)
        var current = file.parent
        while (current != null) {
            val savedOverride = overrides.findNearestScopeOverride(current)
                ?.takeIf { it.scopeDir == overrides.toStoredPath(current.path) }
            val l10nConfig = configFinder.findNearest(current)
                ?.takeIf { it.configDir.path == current.path }

            if (savedOverride != null || l10nConfig != null) {
                return ArbResolutionScope(
                    scopeDir = current,
                    searchRoot = l10nConfig?.arbDir ?: current,
                    savedOverride = savedOverride,
                    l10nConfig = l10nConfig
                )
            }
            current = current.parent
        }

        val fallbackScope = when {
            file.parent != null && ArbLocaleHelper.looksLikeLocale(file.parent.name) && file.parent.parent != null -> file.parent.parent
            else -> file.parent ?: file
        }
        return ArbResolutionScope(
            scopeDir = fallbackScope,
            searchRoot = fallbackScope,
            savedOverride = null,
            l10nConfig = null
        )
    }

    fun resolveTarget(project: Project, targetFile: VirtualFile): ArbTargetResolution {
        val overrides = ArbProjectOverridesService.getInstance(project)
        val scope = resolveScope(project, targetFile)

        val savedBase = overrides.resolveBaseFile(scope.savedOverride)
        val l10nBase = scope.l10nConfig?.arbDir?.findChild(scope.l10nConfig.templateArbFile)
        val heuristicCandidates = if (savedBase == null && l10nBase == null) {
            ArbHeuristics.findBaseCandidates(
                targetFile,
                ArbHeuristics.ScopeContext(scope.scopeDir, scope.searchRoot, scope.l10nConfig)
            )
        } else {
            emptyList()
        }

        val baseCandidates = when {
            savedBase != null -> listOf(savedBase)
            l10nBase != null -> listOf(l10nBase)
            else -> heuristicCandidates
        }.distinctBy { it.path }

        val baseFile = baseCandidates.singleOrNull()
        val baseOrigin = when {
            savedBase != null -> BaseResolutionOrigin.SAVED_OVERRIDE
            l10nBase != null -> BaseResolutionOrigin.L10N_CONFIG
            baseFile != null -> BaseResolutionOrigin.HEURISTIC
            else -> BaseResolutionOrigin.NONE
        }

        val sourceLang = baseFile?.let { resolveSourceLang(scope, it) }
            ?: scope.savedOverride?.sourceLang?.takeIf { it.isNotBlank() }
        val targetLang = resolveTargetLang(overrides, scope, targetFile)

        return ArbTargetResolution(
            scope = scope,
            targetFile = targetFile,
            baseCandidates = baseCandidates,
            baseFile = baseFile,
            baseOrigin = baseOrigin,
            sourceLang = sourceLang,
            targetLang = targetLang,
            needsBasePrompt = baseFile == null,
            needsSourceLangPrompt = sourceLang.isNullOrBlank(),
            needsTargetLangPrompt = targetLang.isNullOrBlank()
        )
    }

    fun resolveScopeBase(project: Project, file: VirtualFile): VirtualFile? {
        val scope = resolveScope(project, file)
        val overrides = ArbProjectOverridesService.getInstance(project)
        return overrides.resolveBaseFile(scope.savedOverride)
            ?: scope.l10nConfig?.arbDir?.findChild(scope.l10nConfig.templateArbFile)
            ?: ArbHeuristics.findBaseCandidates(
                file,
                ArbHeuristics.ScopeContext(scope.scopeDir, scope.searchRoot, scope.l10nConfig)
            ).singleOrNull()
            ?: file.takeIf { ArbHeuristics.isLikelyBaseFile(it, scope.searchRoot) }
    }

    fun isScopeBase(project: Project, file: VirtualFile): Boolean {
        val scopeBase = resolveScopeBase(project, file) ?: return false
        return scopeBase.path == file.path
    }

    fun inferTargetFiles(project: Project, baseFile: VirtualFile): List<VirtualFile> {
        val scope = resolveScope(project, baseFile)
        return ArbHeuristics.collectArbFiles(scope.searchRoot)
            .asSequence()
            .filter { it.path != baseFile.path }
            .filterNot { ArbHeuristics.hasPreferredSourceLocale(it) }
            .filter { candidate ->
                resolveScopeBase(project, candidate)?.path == baseFile.path ||
                    ArbHeuristics.matchesExplicitBase(baseFile, candidate)
            }
            .distinctBy { it.path }
            .sortedBy { it.path }
            .toList()
    }

    private fun resolveSourceLang(scope: ArbResolutionScope, baseFile: VirtualFile): String? {
        return analyzer.parse(baseFile).locale
            ?: scope.savedOverride?.sourceLang?.takeIf { it.isNotBlank() }
            ?: ArbLocaleHelper.guessLocaleFromPath(baseFile)
    }

    private fun resolveTargetLang(
        overrides: ArbProjectOverridesService,
        scope: ArbResolutionScope,
        targetFile: VirtualFile
    ): String? {
        return analyzer.parse(targetFile).locale
            ?: overrides.resolveTargetLanguage(scope.savedOverride, targetFile)
            ?: ArbLocaleHelper.guessLocaleFromPath(targetFile)
    }
}
