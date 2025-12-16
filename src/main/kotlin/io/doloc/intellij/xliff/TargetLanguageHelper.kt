package io.doloc.intellij.xliff

enum class TargetLanguageAttribute(val attributeName: String, val elementName: String) {
    XLIFF12_FILE("target-language", "file"),
    XLIFF20_ROOT("trgLang", "xliff")
}

object TargetLanguageHelper {
    private val LANGUAGE_SUFFIX_PATTERN =
        Regex("(?:^|[._-])([A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8})?)$", RegexOption.IGNORE_CASE)

    fun guessLanguageFromFilename(fileName: String): String? {
        if (fileName.isBlank()) return null
        val lastDot = fileName.lastIndexOf('.')
        val baseName = if (lastDot <= 0) fileName else fileName.substring(0, lastDot)
        val match = LANGUAGE_SUFFIX_PATTERN.find(baseName) ?: return null
        val candidate = match.groupValues.getOrNull(1)?.replace('_', '-')?.trim().orEmpty()
        return candidate.takeIf { it.isNotBlank() }
    }

    fun addTargetLanguageAttribute(
        content: String,
        attribute: TargetLanguageAttribute,
        language: String
    ): String? {
        if (content.isEmpty()) return null
        val normalizedLanguage = language.trim()
        if (normalizedLanguage.isEmpty()) return null
        return insertAttribute(content, attribute.elementName, attribute.attributeName, normalizedLanguage)
    }

    private fun insertAttribute(
        content: String,
        tagName: String,
        attributeName: String,
        attributeValue: String
    ): String? {
        val startIndex = content.indexOf("<$tagName")
        if (startIndex == -1) return null
        val endIndex = content.indexOf('>', startIndex)
        if (endIndex == -1) return null
        val originalTag = content.substring(startIndex, endIndex + 1)
        val attributeRegex = Regex("\\b$attributeName\\s*=", RegexOption.IGNORE_CASE)
        if (attributeRegex.containsMatchIn(originalTag)) {
            return content
        }
        val insertPosition = if (originalTag.endsWith("/>")) {
            originalTag.length - 2
        } else {
            originalTag.length - 1
        }
        val updatedTag = StringBuilder(originalTag)
            .insert(insertPosition, " $attributeName=\"$attributeValue\"")
            .toString()
        return buildString(content.length + attributeName.length + attributeValue.length + 4) {
            append(content, 0, startIndex)
            append(updatedTag)
            append(content, endIndex + 1, content.length)
        }
    }
}
