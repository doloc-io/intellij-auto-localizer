package io.doloc.intellij.xliff

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TargetLanguageHelperTest {

    @Test
    fun `should guess locale from filename`() {
        assertEquals("en", TargetLanguageHelper.guessLanguageFromFilename("messages.en.xlf"))
        assertEquals("en-US", TargetLanguageHelper.guessLanguageFromFilename("i18n.en-US.xlf"))
    }

    @Test
    fun `should return null when locale missing`() {
        assertNull(TargetLanguageHelper.guessLanguageFromFilename("messages.xlf"))
    }

    @Test
    fun `should add trgLang to xliff root`() {
        val original = """<?xml version=\"1.0\"?>\n<xliff version=\"2.0\" srcLang=\"en\">\n  <file id=\"f1\">\n  </file>\n</xliff>"""
        val updated = TargetLanguageHelper.addTargetLanguageAttribute(
            original,
            TargetLanguageAttribute.XLIFF20_ROOT,
            "fr"
        )
        assertNotNull(updated)
        assertTrue(updated.contains("trgLang=\"fr\""))
    }

    @Test
    fun `should add target-language to file element`() {
        val original = """<xliff version=\"1.2\">\n  <file source-language=\"en\">\n  </file>\n</xliff>"""
        val updated = TargetLanguageHelper.addTargetLanguageAttribute(
            original,
            TargetLanguageAttribute.XLIFF12_FILE,
            "de"
        )
        assertNotNull(updated)
        assertTrue(updated.contains("target-language=\"de\""))
    }
}
