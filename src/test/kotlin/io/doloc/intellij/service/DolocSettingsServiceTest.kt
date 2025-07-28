package io.doloc.intellij.service

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.doloc.intellij.settings.DolocSettingsState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DolocSettingsServiceTest : BasePlatformTestCase() {

    private lateinit var service: DolocSettingsService

    override fun setUp() {
        super.setUp()
        // Use the real service since we're testing integration with PasswordSafe
        service = DolocSettingsService.getInstance()
        DolocSettingsState.getInstance().useAnonymousToken = false
    }

    override fun tearDown() {
        // Clean up after tests
        service.clearApiToken()
        DolocSettingsState.getInstance().useAnonymousToken = true
        super.tearDown()
    }

    @Test
    fun testSetAndGetToken() {
        val testToken = "test-api-token-12345"

        // Set the token
        service.setApiToken(testToken)

        // Verify it's stored correctly
        assertEquals(testToken, service.getApiToken())
    }

    @Test
    fun testClearToken() {
        // Set a token first
        service.setApiToken("some-token")

        // Now clear it
        service.clearApiToken()

        // Verify it's gone
        assertNull(service.getApiToken())
    }

    @Test
    fun testTokenFlow() {
        runBlocking {
            val testToken = "flow-test-token"

            // Set the token
            service.setApiToken(testToken)

            // Verify the flow has the new value
            assertEquals(testToken, service.tokenFlow.first())

            // Clear and verify the flow updates
            service.clearApiToken()
            assertNull(service.tokenFlow.first())
        }
    }

    @Test
    fun testGetApiTokenManualVsAnonymous() {
        val manualToken = "manual-token"
        val anonymousToken = "anon-token"

        // Store both tokens
        service.setApiToken(manualToken)
        service.setAnonymousToken(anonymousToken)

        // Manual mode should return manual token
        DolocSettingsState.getInstance().useAnonymousToken = false
        assertEquals(manualToken, service.getApiToken())

        // Anonymous mode should return anonymous token
        DolocSettingsState.getInstance().useAnonymousToken = true
        assertEquals(anonymousToken, service.getApiToken())
    }
}
