package io.doloc.intellij.service

import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DolocSettingsServiceFallbackTest {

    @After
    fun tearDown() {
        DolocSettingsService.resetTokenStoreFactory()
        DolocSettingsService.resetTokenStoreFailureNotifier()
    }

    @Test
    fun testFallsBackToMemoryWhenTokenStoreIsUnavailable() {
        DolocSettingsService.tokenStoreFactory = { FailingTokenStore() }

        val service = DolocSettingsService()
        service.setApiToken("manual-token")
        service.setAnonymousToken("anonymous-token")

        assertEquals("manual-token", service.getStoredManualToken())
        assertEquals("anonymous-token", service.getStoredAnonymousToken())

        service.clearApiToken()
        service.clearAnonymousToken()

        assertNull(service.getStoredManualToken())
        assertNull(service.getStoredAnonymousToken())
    }

    @Test
    fun testNotifiesOnlyOnceWhenTokenStoreIsUnavailable() {
        DolocSettingsService.tokenStoreFactory = { FailingTokenStore() }
        var notificationCount = 0
        var receivedFailure: Throwable? = null
        DolocSettingsService.tokenStoreFailureNotifier = {
            notificationCount += 1
            receivedFailure = it
        }

        val service = DolocSettingsService()
        service.setApiToken("manual-token")
        service.getStoredManualToken()
        service.setAnonymousToken("anonymous-token")

        assertEquals(1, notificationCount)
        assertTrue(receivedFailure is UnsatisfiedLinkError)
    }

    private class FailingTokenStore : TokenStore {
        override fun read(key: String): String? = throw UnsatisfiedLinkError("missing native token store")

        override fun write(key: String, token: String?) {
            throw UnsatisfiedLinkError("missing native token store")
        }
    }
}
