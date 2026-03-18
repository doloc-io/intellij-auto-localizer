package io.doloc.intellij.auth

import io.doloc.intellij.api.DolocRequestMetadata
import io.doloc.intellij.service.DolocSettingsService
import io.doloc.intellij.test.TestHttpServer
import io.doloc.intellij.test.TestResponse
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals

class AnonymousTokenManagerTest {
    private lateinit var mockWebServer: TestHttpServer
    private lateinit var mockSettingsService: DolocSettingsService
    private lateinit var tokenManager: AnonymousTokenManager

    @Before
    fun setUp() {
        mockWebServer = TestHttpServer()
        mockWebServer.start()

        // Create mocked settings service
        mockSettingsService = mock()

        // Create token manager that points to our mock server
        tokenManager = AnonymousTokenManager(
            settingsService = mockSettingsService,
            baseUrlProvider = { mockWebServer.baseUrl }
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
        val responseBody = """{"token":"new-anonymous-token","user_id":"1234-1234-1234-1234"}"""
        mockWebServer.enqueue(
            TestResponse(
                statusCode = 200,
                body = responseBody,
                headers = mapOf("Content-Type" to "application/json")
            )
        )

        // Execute
        val result = tokenManager.getOrCreateToken()

        // Verify
        assertEquals("new-anonymous-token", result)
        verify(mockSettingsService).setAnonymousToken("new-anonymous-token")

        // Verify the request
        val recordedRequest = mockWebServer.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        kotlin.test.assertNotNull(recordedRequest)
        assertEquals("PUT", recordedRequest.method)
        assertEquals("/token/anonymous", recordedRequest.path)
        assertEquals(DolocRequestMetadata.userAgent(), recordedRequest.getHeader("User-Agent"))
        assertEquals(
            DolocRequestMetadata.pluginVersion(),
            recordedRequest.getHeader(DolocRequestMetadata.VERSION_HEADER_NAME)
        )
    }
}
