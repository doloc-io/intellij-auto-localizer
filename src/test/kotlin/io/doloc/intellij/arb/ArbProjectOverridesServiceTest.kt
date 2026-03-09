package io.doloc.intellij.arb

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Test
import kotlin.test.assertNotNull

class ArbProjectOverridesServiceTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        ArbProjectOverridesService.getInstance(project).replaceScopes(emptyList())
    }

    override fun tearDown() {
        try {
            ArbProjectOverridesService.getInstance(project).replaceScopes(emptyList())
        } finally {
            super.tearDown()
        }
    }

    @Test
    fun testStateSerializesWithoutProjectReference() {
        val service = ArbProjectOverridesService.getInstance(project)
        service.replaceScopes(
            listOf(
                ArbProjectOverridesService.ArbScopeOverride(
                    scopeDir = "feature",
                    baseFile = "lib/l10n/app_en.arb",
                    sourceLang = "en",
                    targetOverrides = mutableListOf(
                        ArbProjectOverridesService.ArbTargetOverride(
                            targetFile = "lib/l10n/app_de.arb",
                            targetLang = "de"
                        )
                    )
                )
            )
        )

        val serialized = XmlSerializer.serialize(service.state)

        assertNotNull(serialized)
    }
}
