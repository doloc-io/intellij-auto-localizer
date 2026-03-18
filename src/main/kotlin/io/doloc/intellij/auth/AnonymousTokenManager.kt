package io.doloc.intellij.auth

import com.intellij.openapi.diagnostic.Logger
import io.doloc.intellij.api.DolocRequestBuilder
import io.doloc.intellij.api.DolocRequestMetadata
import io.doloc.intellij.http.HttpClientProvider
import io.doloc.intellij.service.DolocSettingsService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Serializable
private data class AnonymousTokenResponse(
    val token: String,
    val user_id: String
)

class AnonymousTokenManager(
    private val settingsService: DolocSettingsService,
    private val baseUrlProvider: () -> String = { DolocRequestBuilder.getBaseUrl() }
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val log = Logger.getInstance(AnonymousTokenManager::class.java)

    /**
     * Gets existing token from settings or creates a new anonymous one
     * @return API token string
     */
    suspend fun getOrCreateToken(): String {
        val existingToken = settingsService.getStoredAnonymousToken()
        if (!existingToken.isNullOrBlank()) {
            return existingToken
        }

        return createToken()
    }

    suspend fun createToken(): String {
        log.info("Requesting new anonymous token")

        val request = HttpRequest.newBuilder()
            .uri(URI("${baseUrlProvider()}/token/anonymous"))
            .header("User-Agent", DolocRequestMetadata.userAgent())
            .header(DolocRequestMetadata.VERSION_HEADER_NAME, DolocRequestMetadata.pluginVersion())
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build()

        val client = HttpClientProvider.client
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() >= 300) {
            throw IllegalStateException("Failed to get anonymous token: HTTP ${response.statusCode()}")
        }

        val tokenResponse = json.decodeFromString<AnonymousTokenResponse>(response.body())

        settingsService.setAnonymousToken(tokenResponse.token)

        log.info("Created new anonymous token")
        return tokenResponse.token
    }
}
