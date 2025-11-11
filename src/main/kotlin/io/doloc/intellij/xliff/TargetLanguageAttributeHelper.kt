package io.doloc.intellij.xliff

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Inserts missing target language attributes into XLIFF files and navigates the user to the new value.
 */
object TargetLanguageAttributeHelper {

    fun addMissingTargetLanguageAttribute(
        project: Project,
        file: VirtualFile,
        isXliff2: Boolean
    ): Void {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        val tagName = if (isXliff2) "xliff" else "file"
        val attributeName = if (isXliff2) "trgLang" else "target-language"

        val insertOffset = findInsertionOffset(document.text, tagName) ?: return false
        val attributeValue = guessLanguageFromFilename(file.name) ?: ""
        val attributeText = buildAttribute(attributeName, attributeValue)

        var caretOffset = insertOffset
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(insertOffset, attributeText)
            FileDocumentManager.getInstance().saveDocument(document)
        }

        caretOffset += attributeText.indexOf('"') + 1
        OpenFileDescriptor(project, file, caretOffset).navigate(true)
        return true
    }

    private fun buildAttribute(name: String, value: String): String = """ $name="$value""""

    private fun findInsertionOffset(text: String, tagName: String): Int? {
        val regex = Regex("<\\s*$tagName\\b", RegexOption.IGNORE_CASE)
        val match = regex.find(text) ?: return null
        var idx = match.range.last + 1
        while (idx < text.length && text[idx] != '>') {
            idx++
        }
        if (idx >= text.length) return null

        var insertOffset = idx
        var searchIdx = idx - 1
        while (searchIdx > match.range.first && text[searchIdx].isWhitespace()) {
            searchIdx--
        }
        if (searchIdx > match.range.first && text[searchIdx] == '/') {
            insertOffset = searchIdx
        }
        return insertOffset
    }

    internal fun guessLanguageFromFilename(filename: String): String? {
        val baseName = filename.substringBeforeLast('.', filename)
        val delimiterIndex = listOf('.', '_', '-').map { baseName.lastIndexOf(it) }.maxOrNull() ?: -1
        val candidate = if (delimiterIndex == -1) baseName else baseName.substring(delimiterIndex + 1)
        if (candidate.isEmpty()) {
            return null
        }
        val normalized = candidate.replace('_', '-').trim()
        if (!LANGUAGE_CANDIDATE_PATTERN.matches(normalized)) {
            return null
        }
        val parts = normalized.split('-').filter { it.isNotBlank() }
        if (parts.isEmpty()) {
            return null
        }
        val lang = parts.first().lowercase()
        val region = parts.drop(1).joinToString("-") { it.uppercase() }
        return if (region.isEmpty()) lang else "$lang-$region"
    }

    private val LANGUAGE_CANDIDATE_PATTERN = Regex("^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{2,8})?$")
}
