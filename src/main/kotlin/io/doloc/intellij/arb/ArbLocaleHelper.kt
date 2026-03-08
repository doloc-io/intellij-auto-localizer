package io.doloc.intellij.arb

import com.intellij.openapi.vfs.VirtualFile

object ArbLocaleHelper {
    private val LOCALE_SUFFIX_PATTERN =
        Regex("[._-]([A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8}){0,2})$", RegexOption.IGNORE_CASE)
    private val LOCALE_ONLY_PATTERN =
        Regex("^[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8}){0,2}$", RegexOption.IGNORE_CASE)

    fun normalizeLocale(candidate: String?): String? {
        val normalized = candidate?.trim()?.replace('_', '-')?.takeIf { it.isNotBlank() } ?: return null
        return normalized.takeIf { looksLikeLocale(it) }
    }

    fun looksLikeLocale(candidate: String?): Boolean {
        val value = candidate?.trim().orEmpty()
        return value.isNotBlank() && LOCALE_ONLY_PATTERN.matches(value)
    }

    fun guessLocaleFromFilename(fileName: String): String? {
        if (!fileName.endsWith(".arb", ignoreCase = true)) return null
        val baseName = fileName.removeSuffix(".arb").removeSuffix(".ARB")
        val match = LOCALE_SUFFIX_PATTERN.find(baseName) ?: return null
        return normalizeLocale(match.groupValues.getOrNull(1))
    }

    fun guessLocaleFromDirectory(file: VirtualFile): String? {
        return normalizeLocale(file.parent?.name)
    }

    fun guessLocaleFromPath(file: VirtualFile): String? {
        return guessLocaleFromFilename(file.name) ?: guessLocaleFromDirectory(file)
    }

    fun replaceFilenameLocale(fileName: String, locale: String): String? {
        if (!fileName.endsWith(".arb", ignoreCase = true)) return null
        val baseName = fileName.substring(0, fileName.length - 4)
        val match = LOCALE_SUFFIX_PATTERN.find(baseName) ?: return null
        val candidate = normalizeLocale(locale) ?: return null
        val replaced = buildString {
            append(baseName.substring(0, match.range.first))
            append(match.value.replace(match.groupValues[1], candidate))
        }
        return "$replaced.arb"
    }
}
