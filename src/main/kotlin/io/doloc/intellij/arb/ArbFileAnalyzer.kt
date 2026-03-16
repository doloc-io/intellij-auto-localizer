package io.doloc.intellij.arb

import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

class ArbFileAnalyzer(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
) {
    data class ArbDocument(
        val locale: String?,
        val messages: Map<String, String>
    )

    data class AnalysisResult(
        val untranslatedKeys: Set<String>
    ) {
        val hasUntranslatedKeys: Boolean
            get() = untranslatedKeys.isNotEmpty()
    }

    fun parse(file: VirtualFile): ArbDocument {
        return try {
            parse(VfsUtil.loadText(file))
        } catch (e: ArbParseException) {
            throw e
        } catch (e: Exception) {
            throw ArbParseException(file.name, e)
        }
    }

    fun parse(content: String): ArbDocument {
        val root = json.parseToJsonElement(content).jsonObject
        return ArbDocument(
            locale = extractLocale(root),
            messages = extractMessages(root)
        )
    }

    fun analyze(baseFile: VirtualFile, targetFile: VirtualFile, untranslatedRules: Set<String>): AnalysisResult {
        return analyze(parse(baseFile), parse(targetFile), untranslatedRules)
    }

    fun analyze(baseDocument: ArbDocument, targetDocument: ArbDocument, untranslatedRules: Set<String>): AnalysisResult {
        val untranslatedKeys = linkedSetOf<String>()
        for ((key, sourceValue) in baseDocument.messages) {
            val targetValue = targetDocument.messages[key]
            when {
                targetValue == null && "missing" in untranslatedRules -> untranslatedKeys += key
                targetValue != null && targetValue.isBlank() && "empty" in untranslatedRules -> untranslatedKeys += key
                targetValue != null && targetValue == sourceValue && "equal" in untranslatedRules -> untranslatedKeys += key
            }
        }
        return AnalysisResult(untranslatedKeys)
    }

    private fun extractLocale(root: JsonObject): String? {
        val localeValue = (root["@@locale"] as? JsonPrimitive)?.contentOrNull
        return ArbLocaleHelper.normalizeLocale(localeValue)
    }

    private fun extractMessages(root: JsonObject): Map<String, String> {
        val messages = linkedMapOf<String, String>()
        for ((key, value) in root) {
            if (key.startsWith("@")) continue
            val primitive = value as? JsonPrimitive ?: continue
            if (!primitive.isString) continue
            messages[key] = primitive.content
        }
        return messages
    }
}
