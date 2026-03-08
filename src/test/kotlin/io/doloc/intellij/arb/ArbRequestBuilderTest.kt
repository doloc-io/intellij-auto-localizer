package io.doloc.intellij.arb

import com.intellij.mock.MockVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.doloc.intellij.api.DolocRequestBuilder
import io.doloc.intellij.service.DolocSettingsService
import io.doloc.intellij.settings.DolocSettingsState
import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArbRequestBuilderTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        DolocSettingsService.getInstance().setApiToken("test-token")
        DolocSettingsState.getInstance().useAnonymousToken = false
    }

    override fun tearDown() {
        try {
            DolocSettingsService.getInstance().clearApiToken()
            DolocSettingsState.getInstance().useAnonymousToken = true
        } finally {
            super.tearDown()
        }
    }

    fun testCreateArbTranslationRequestBuildsMultipartBody() {
        val source = MockVirtualFile("app_en.arb", "{" + "\"@@locale\":\"en\",\"hello\":\"Hello\"}")
        val target = MockVirtualFile("app_de.arb", "{" + "\"@@locale\":\"de\",\"hello\":\"\"}")

        val request = DolocRequestBuilder.createArbTranslationRequest(
            sourceFile = source,
            targetFile = target,
            untranslatedStates = setOf("missing", "empty"),
            sourceLang = "en",
            targetLang = "de"
        )

        assertEquals("POST", request.method())
        assertEquals("Bearer test-token", request.headers().firstValue("Authorization").orElse(null))
        assertTrue(request.uri().toString().contains("untranslated=missing,empty"))
        assertTrue(request.uri().toString().contains("sourceLang=en"))
        assertTrue(request.uri().toString().contains("targetLang=de"))

        val contentType = request.headers().firstValue("Content-Type").orElse("")
        assertTrue(contentType.startsWith("multipart/form-data; boundary="))

        val body = request.bodyPublisher().orElseThrow().readAsString()
        assertTrue(body.contains("name=\"source\"; filename=\"app_en.arb\""))
        assertTrue(body.contains("name=\"target\"; filename=\"app_de.arb\""))
        assertTrue(body.contains("\"hello\":\"Hello\""))
    }

    private fun HttpRequest.BodyPublisher.readAsString(): String {
        val future = CompletableFuture<ByteArray>()
        val buffers = mutableListOf<ByteArray>()
        subscribe(object : Flow.Subscriber<ByteBuffer> {
            private lateinit var subscription: Flow.Subscription

            override fun onSubscribe(subscription: Flow.Subscription) {
                this.subscription = subscription
                subscription.request(Long.MAX_VALUE)
            }

            override fun onNext(item: ByteBuffer) {
                val bytes = ByteArray(item.remaining())
                item.get(bytes)
                buffers += bytes
            }

            override fun onError(throwable: Throwable) {
                future.completeExceptionally(throwable)
            }

            override fun onComplete() {
                val combined = ByteArray(buffers.sumOf { it.size })
                var offset = 0
                buffers.forEach { bytes ->
                    bytes.copyInto(combined, offset)
                    offset += bytes.size
                }
                future.complete(combined)
                subscription.cancel()
            }
        })
        return String(future.get())
    }
}
