package io.doloc.intellij.api

import com.intellij.openapi.vfs.VirtualFile
import io.doloc.intellij.service.DolocSettingsService
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpRequest

/**
 * Builds HTTP requests for doloc API
 */
object DolocRequestBuilder {
    private var BASE_URL = "https://api.doloc.io"

    /**
     * Creates a translation request for the given file
     * @param filePath Path to the file to translate
     * @param untranslatedStates Optional set of states to consider untranslated
     * @param newState Optional state to set for translated units
     * @return HttpRequest configured for translation
     * @throws IllegalStateException if no API token is available
     */
    fun createTranslationRequest(
        filePath: VirtualFile,
        untranslatedStates: Set<String>? = null,
        newState: String? = null
    ): HttpRequest {
        val token = DolocSettingsService.getInstance().getApiToken()
            ?: throw IllegalStateException("No API token available")

        val queryString = DolocQueryBuilder.buildTranslateQueryString(
            untranslated = untranslatedStates,
            newState = newState
        )

        return HttpRequest.newBuilder()
            .uri(URI("$BASE_URL$queryString"))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(filePath.contentsToByteArray()))
            .build()
    }

    fun createArbTranslationRequest(
        sourceFile: VirtualFile,
        targetFile: VirtualFile,
        untranslatedStates: Set<String>,
        sourceLang: String?,
        targetLang: String?
    ): HttpRequest {
        val token = DolocSettingsService.getInstance().getApiToken()
            ?: throw IllegalStateException("No API token available")

        val queryString = DolocQueryBuilder.buildArbTranslateQueryString(
            untranslated = untranslatedStates,
            sourceLang = sourceLang,
            targetLang = targetLang
        )

        val boundary = "----DolocBoundary${System.currentTimeMillis()}"
        val body = buildMultipartBody(
            boundary,
            listOf(
                MultipartPart("source", sourceFile.name, sourceFile.contentsToByteArray()),
                MultipartPart("target", targetFile.name, targetFile.contentsToByteArray())
            )
        )

        return HttpRequest.newBuilder()
            .uri(URI("$BASE_URL$queryString"))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/octet-stream")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
    }

    fun setBaseUrl(newBaseUrl: String) {
        if (newBaseUrl.isBlank()) {
            throw IllegalArgumentException("Base URL cannot be blank")
        }
        BASE_URL = newBaseUrl
    }

    fun getBaseUrl(): String {
        return BASE_URL
    }

    private data class MultipartPart(
        val name: String,
        val fileName: String,
        val bytes: ByteArray
    )

    private fun buildMultipartBody(boundary: String, parts: List<MultipartPart>): ByteArray {
        val output = ByteArrayOutputStream()
        parts.forEach { part ->
            output.write("--$boundary\r\n".toByteArray())
            output.write(
                "Content-Disposition: form-data; name=\"${part.name}\"; filename=\"${part.fileName}\"\r\n".toByteArray()
            )
            output.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
            output.write(part.bytes)
            output.write("\r\n".toByteArray())
        }
        output.write("--$boundary--\r\n".toByteArray())
        return output.toByteArray()
    }
}
