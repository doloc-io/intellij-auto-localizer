package io.doloc.intellij.arb

import com.intellij.openapi.vfs.VirtualFile

object ArbHeuristics {
    private val preferredSourceLocales = listOf("en", "en-US", "en-GB")

    data class ScopeContext(
        val scopeDir: VirtualFile,
        val searchRoot: VirtualFile,
        val config: FlutterL10nConfigFinder.FlutterL10nConfig?
    )

    fun findBaseCandidates(targetFile: VirtualFile, scopeContext: ScopeContext): List<VirtualFile> {
        val arbFiles = collectArbFiles(scopeContext.searchRoot)
        val candidates = linkedMapOf<String, VirtualFile>()

        standardFlutterCandidate(targetFile, scopeContext)?.let { candidates[it.path] = it }
        addDirectoryLocaleCandidates(targetFile, candidates)
        addFilenameLocaleCandidates(targetFile, arbFiles, candidates)
        addSameFamilyCandidates(targetFile, arbFiles, candidates)

        return candidates.values.toList()
    }

    private fun standardFlutterCandidate(targetFile: VirtualFile, scopeContext: ScopeContext): VirtualFile? {
        val config = scopeContext.config
        if (config != null) {
            return config.arbDir?.findChild(config.templateArbFile)
        }
        val looksFlutterLike = targetFile.name.startsWith("app_")
        if (!looksFlutterLike) {
            return null
        }
        return scopeContext.searchRoot.findFileByRelativePath("lib/l10n/app_en.arb")
            ?: scopeContext.searchRoot.findChild("app_en.arb")
    }

    private fun addDirectoryLocaleCandidates(
        targetFile: VirtualFile,
        candidates: MutableMap<String, VirtualFile>
    ) {
        val parent = targetFile.parent ?: return
        val targetLocale = ArbLocaleHelper.guessLocaleFromDirectory(targetFile) ?: return
        val grandParent = parent.parent ?: return
        val relativeName = targetFile.name

        preferredSourceLocales.forEach { locale ->
            if (locale.equals(targetLocale, ignoreCase = true)) return@forEach
            val candidate = grandParent.findChild(locale)?.findChild(relativeName)
            if (candidate != null && !candidate.isDirectory) {
                candidates[candidate.path] = candidate
            }
        }
    }

    private fun addFilenameLocaleCandidates(
        targetFile: VirtualFile,
        arbFiles: List<VirtualFile>,
        candidates: MutableMap<String, VirtualFile>
    ) {
        preferredSourceLocales.forEach { locale ->
            val candidateName = ArbLocaleHelper.replaceFilenameLocale(targetFile.name, locale) ?: return@forEach
            val sameDirectoryCandidate = targetFile.parent?.findChild(candidateName)
            if (sameDirectoryCandidate != null && !sameDirectoryCandidate.isDirectory) {
                candidates[sameDirectoryCandidate.path] = sameDirectoryCandidate
                return@forEach
            }

            val matches = arbFiles.filter { it.name.equals(candidateName, ignoreCase = true) }
            if (matches.size == 1) {
                candidates[matches.single().path] = matches.single()
            }
        }
    }

    private fun addSameFamilyCandidates(
        targetFile: VirtualFile,
        arbFiles: List<VirtualFile>,
        candidates: MutableMap<String, VirtualFile>
    ) {
        val targetLocale = ArbLocaleHelper.guessLocaleFromPath(targetFile)
        val sameFamily = arbFiles.filter { it.path != targetFile.path }.filter { candidate ->
            sameFamily(candidate.name, targetFile.name) &&
                preferredSourceLocales.any { locale ->
                    ArbLocaleHelper.guessLocaleFromPath(candidate)?.equals(locale, ignoreCase = true) == true
                } &&
                ArbLocaleHelper.guessLocaleFromPath(candidate)?.equals(targetLocale, ignoreCase = true) != true
        }
        if (sameFamily.size == 1) {
            val candidate = sameFamily.single()
            candidates[candidate.path] = candidate
        }
    }

    fun collectArbFiles(root: VirtualFile): List<VirtualFile> {
        val files = mutableListOf<VirtualFile>()
        collectRecursively(root, files)
        return files
    }

    fun isLikelyBaseFile(file: VirtualFile, searchRoot: VirtualFile): Boolean {
        val arbFiles = collectArbFiles(searchRoot)
        return isLikelyBaseFile(file, arbFiles)
    }

    private fun isLikelyBaseFile(file: VirtualFile, arbFiles: List<VirtualFile>): Boolean {
        val locale = ArbLocaleHelper.guessLocaleFromPath(file) ?: return false
        if (preferredSourceLocales.none { it.equals(locale, ignoreCase = true) }) {
            return false
        }

        if (arbFiles.any { candidate ->
                candidate.path != file.path && sameFamily(candidate.name, file.name)
        }) {
            return true
        }

        val parent = file.parent ?: return false
        if (!ArbLocaleHelper.looksLikeLocale(parent.name)) {
            return false
        }
        val grandParent = parent.parent ?: return false
        return grandParent.children.any { siblingDir ->
            siblingDir.isDirectory &&
                siblingDir.path != parent.path &&
                ArbLocaleHelper.looksLikeLocale(siblingDir.name) &&
                siblingDir.findChild(file.name) != null
        }
    }

    fun hasPreferredSourceLocale(file: VirtualFile): Boolean {
        val locale = ArbLocaleHelper.guessLocaleFromPath(file) ?: return false
        return preferredSourceLocales.any { it.equals(locale, ignoreCase = true) }
    }

    fun matchesExplicitBase(baseFile: VirtualFile, candidate: VirtualFile): Boolean {
        if (candidate.path == baseFile.path) {
            return false
        }

        val candidateLocale = ArbLocaleHelper.guessLocaleFromPath(candidate)
        if (candidateLocale != null && preferredSourceLocales.any { it.equals(candidateLocale, ignoreCase = true) }) {
            return false
        }

        val baseParent = baseFile.parent
        val candidateParent = candidate.parent
        if (baseParent != null && candidateParent != null &&
            ArbLocaleHelper.looksLikeLocale(baseParent.name) &&
            ArbLocaleHelper.looksLikeLocale(candidateParent.name) &&
            baseParent.parent?.path == candidateParent.parent?.path &&
            baseFile.name == candidate.name
        ) {
            return true
        }

        return sameFamily(baseFile.name, candidate.name)
    }

    private fun collectRecursively(current: VirtualFile, files: MutableList<VirtualFile>) {
        if (!current.isValid) return
        if (current.isDirectory) {
            current.children.forEach { child -> collectRecursively(child, files) }
            return
        }
        if (current.extension.equals("arb", ignoreCase = true)) {
            files += current
        }
    }

    private fun sameFamily(leftName: String, rightName: String): Boolean {
        val leftStem = removeLocaleSuffix(leftName)
        val rightStem = removeLocaleSuffix(rightName)
        return leftStem != null && leftStem == rightStem
    }

    private fun removeLocaleSuffix(fileName: String): String? {
        val candidate = fileName.takeIf { it.endsWith(".arb", ignoreCase = true) } ?: return null
        val baseName = candidate.substring(0, candidate.length - 4)
        val locale = ArbLocaleHelper.guessLocaleFromFilename(candidate) ?: return baseName
        val suffixIndex = baseName.lastIndexOf(locale.replace('-', '_'))
            .takeIf { it >= 0 }
            ?: baseName.lastIndexOf(locale)
        return if (suffixIndex > 0) {
            baseName.substring(0, suffixIndex).trimEnd('.', '_', '-')
        } else {
            baseName
        }
    }
}
