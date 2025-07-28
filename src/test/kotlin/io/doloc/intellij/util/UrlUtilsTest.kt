package io.doloc.intellij.util

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class UrlUtilsTest {
    @Test
    fun `utmUrl places parameters before fragment`() {
        val url = utmUrl("https://doloc.io/pricing/#source-texts", "test")
        assertTrue(url.contains(BASE_UTM))
        assertTrue(url.contains("utm_content=test"))
        val question = url.indexOf('?')
        val hash = url.indexOf('#')
        assertTrue(question in 0 until hash)
        assertEquals("#source-texts", url.substring(hash))
    }
}
