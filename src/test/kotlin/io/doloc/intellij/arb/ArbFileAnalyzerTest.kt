package io.doloc.intellij.arb

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArbFileAnalyzerTest {
    private val analyzer = ArbFileAnalyzer()

    @Test
    fun `should parse locale and translatable keys`() {
        val document = analyzer.parse(
            """
            {
              "@@locale": "en",
              "title": "Hello",
              "@title": { "description": "Greeting" },
              "count": 1,
              "empty": ""
            }
            """.trimIndent()
        )

        assertEquals("en", document.locale)
        assertEquals(mapOf("title" to "Hello", "empty" to ""), document.messages)
    }

    @Test
    fun `should detect missing empty and equal translations`() {
        val base = analyzer.parse(
            """
            {
              "@@locale": "en",
              "hello": "Hello",
              "bye": "Bye",
              "same": "Same"
            }
            """.trimIndent()
        )
        val target = analyzer.parse(
            """
            {
              "@@locale": "de",
              "hello": "",
              "same": "Same"
            }
            """.trimIndent()
        )

        val result = analyzer.analyze(base, target, setOf("missing", "empty", "equal"))
        assertEquals(setOf("hello", "bye", "same"), result.untranslatedKeys)
        assertTrue(result.hasUntranslatedKeys)
    }
}
