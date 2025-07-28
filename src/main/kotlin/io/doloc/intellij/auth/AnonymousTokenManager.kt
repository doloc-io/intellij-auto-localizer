package io.doloc.intellij.auth

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.Logger
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
    val quota: Int
)

class AnonymousTokenManager(
    private val settingsService: DolocSettingsService,
    private val baseUrl: String = "https://api.doloc.io"
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val log = Logger.getInstance(AnonymousTokenManager::class.java)

    /**
     * Gets existing token from settings or creates a new anonymous one
     * @return API token string
     */
    suspend fun getOrCreateToken(): String {
        // First check if we already have a token
        val existingToken = settingsService.getStoredAnonymousToken()
        if (!existingToken.isNullOrBlank()) {
            return existingToken
        }

        log.info("No API token found, requesting anonymous token")

        // Create a new anonymous token
        val request = HttpRequest.newBuilder()
            .uri(URI("$baseUrl/token/anonymous"))
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build()

        val client = HttpClientProvider.client
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw IllegalStateException("Failed to get anonymous token: HTTP ${response.statusCode()}")
        }

        val tokenResponse = json.decodeFromString<AnonymousTokenResponse>(response.body())

        // Save the new token
        settingsService.setAnonymousToken(tokenResponse.token)

        log.info("Created anonymous token with quota: ${tokenResponse.quota}")
        return tokenResponse.token
    }
}
