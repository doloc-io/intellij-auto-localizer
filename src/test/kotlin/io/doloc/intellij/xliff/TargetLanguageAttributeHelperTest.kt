package io.doloc.intellij.xliff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TargetLanguageAttributeHelperTest {

    @Test
    fun `should guess simple language from filename`() {
        val guess = TargetLanguageAttributeHelper.guessLanguageFromFilename("messages.fr.xlf")
        assertEquals("fr", guess)
    }

    @Test
    fun `should normalize region component`() {
        val guess = TargetLanguageAttributeHelper.guessLanguageFromFilename("labels_en_us.xlf")
        assertEquals("en-US", guess)
    }

    @Test
    fun `should handle hyphen separated locale`() {
        val guess = TargetLanguageAttributeHelper.guessLanguageFromFilename("app-pt-br.xlf")
        assertEquals("pt-BR", guess)
    }

    @Test
    fun `should return null when no language hint present`() {
        val guess = TargetLanguageAttributeHelper.guessLanguageFromFilename("strings.xlf")
        assertNull(guess)
    }
}
