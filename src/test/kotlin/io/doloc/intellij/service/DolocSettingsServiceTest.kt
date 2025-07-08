package io.doloc.intellij.service

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

class DolocSettingsServiceTest : BasePlatformTestCase() {

    private lateinit var service: DolocSettingsService

    override fun setUp() {
        super.setUp()
        // Use the real service since we're testing integration with PasswordSafe
        service = DolocSettingsService.getInstance()
    }

    override fun tearDown() {
        // Clean up after tests
        service.clearApiToken()
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
}
