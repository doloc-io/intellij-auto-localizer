package io.doloc.intellij.auth

import io.doloc.intellij.service.DolocSettingsService
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals

class AnonymousTokenManagerTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var mockSettingsService: DolocSettingsService
    private lateinit var tokenManager: AnonymousTokenManager

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        // Create mocked settings service
        mockSettingsService = mock()

        // Create token manager that points to our mock server
        tokenManager = AnonymousTokenManager(
            settingsService = mockSettingsService,
            baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `should return existing token if available`() = runBlocking {
        // Setup: mock settings to return an existing token
        whenever(mockSettingsService.getStoredAnonymousToken()).thenReturn("existing-token")

        // Execute
        val result = tokenManager.getOrCreateToken()

        // Verify
        assertEquals("existing-token", result)
        verify(mockSettingsService, never()).setAnonymousToken("existing-token")
    }

    @Test
    fun `should create and store new anonymous token if none exists`() = runBlocking {
        // Setup: mock settings to return null (no token)
        whenever(mockSettingsService.getStoredAnonymousToken()).thenReturn(null)

        // Mock the server response
        val responseBody = """{"token":"new-anonymous-token","quota":1000}"""
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(responseBody)
                .addHeader("Content-Type", "application/json")
        )

        // Execute
        val result = tokenManager.getOrCreateToken()

        // Verify
        assertEquals("new-anonymous-token", result)
        verify(mockSettingsService).setAnonymousToken("new-anonymous-token")

        // Verify the request
        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("PUT", recordedRequest.method)
        assertEquals("/token/anonymous", recordedRequest.path)
    }
}
