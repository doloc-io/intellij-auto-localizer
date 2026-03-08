package io.doloc.intellij.arb

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArbLocaleHelperTest {
    @Test
    fun `should infer locale from arb filename`() {
        assertEquals("de", ArbLocaleHelper.guessLocaleFromFilename("app_de.arb"))
        assertEquals("en-US", ArbLocaleHelper.guessLocaleFromFilename("messages.en_US.arb"))
    }

    @Test
    fun `should normalize locales`() {
        assertEquals("pt-BR", ArbLocaleHelper.normalizeLocale("pt_BR"))
        assertTrue(ArbLocaleHelper.looksLikeLocale("zh-Hant-TW"))
    }

    @Test
    fun `should return null for invalid filename locale`() {
        assertNull(ArbLocaleHelper.guessLocaleFromFilename("app.arb"))
        assertNull(ArbLocaleHelper.normalizeLocale("not a locale"))
    }
}
