package io.doloc.intellij.xliff

import com.intellij.mock.MockVirtualFile
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LightweightXliffScannerTest {
    private val scanner = LightweightXliffScanner()
    
    @Test
    fun `should detect empty targets`() {
        val mockVirtualFile = MockVirtualFile("untranslated.xlf",
            """<?xml version="1.0" encoding="UTF-8"?>
<xliff version="1.2">
  <file source-language="en" target-language="fr" datatype="plaintext" original="ng2.template">
    <body>
      <trans-unit id="3">
        <source>Goodbye</source>
        <target></target>
      </trans-unit>
    </body>
  </file>
</xliff>"""
        )
        val result = scanner.scan(
            mockVirtualFile,
            setOf("no-state_empty-target"),
            setOf("no-state_empty-target")
        )
        assertTrue(result.hasUntranslatedUnits)
        assertFalse(result.isXliff2)
        assertTrue(result.hasTargetLanguageAttribute)
    }

    
    @Test
    fun `should detect units that need translation by state`() {
        val mockVirtualFile = MockVirtualFile("untranslated.xlf",
            """<?xml version="1.0" encoding="UTF-8"?>
<xliff version="1.2">
  <file source-language="en" target-language="fr" datatype="plaintext" original="ng2.template">
    <body>
      <trans-unit id="3">
        <source>Goodbye</source>
        <target state="new">asdf</target>
      </trans-unit>
    </body>
  </file>
</xliff>"""
        )
        val result = scanner.scan(mockVirtualFile, setOf("new"), setOf("new"))
        assertTrue(result.hasUntranslatedUnits)
        assertFalse(result.isXliff2)
        assertTrue(result.hasTargetLanguageAttribute)
    }


    @Test
    fun `should not flag fully translated files`() {
        val mockVirtualFile = MockVirtualFile("untranslated.xlf",
            """<?xml version="1.0" encoding="UTF-8"?>
<xliff version="1.2">
  <file source-language="en" target-language="es" datatype="plaintext" original="ng2.template">
    <body>
      <trans-unit id="1" state="translated">
        <source>File</source>
        <target>Archivo</target>
      </trans-unit>
      <trans-unit id="2">
        <source>Edit</source>
        <target>Editar</target>
      </trans-unit>
      <trans-unit id="3">
        <source>View</source>
        <target>Ver</target>
      </trans-unit>
    </body>
  </file>
</xliff>
"""
        )
        val result = scanner.scan(
            mockVirtualFile,
            setOf(
                "new",
                "needs-translation",
                "needs-l10n",
                "needs-adaptation",
                "no-state_target-equals-source",
                "no-state_empty-target"
            ),
            setOf(
                "initial",
                "no-state_target-equals-source",
                "no-state_empty-target"
            )
        )
        assertFalse(result.hasUntranslatedUnits)
        assertFalse(result.isXliff2)
        assertTrue(result.hasTargetLanguageAttribute)
    }


    @Test
    fun `should detect untranslated units in XLIFF 2_0`() {
        val mockVirtualFile = MockVirtualFile("untranslated.xlf",
            """<?xml version="1.0" encoding="UTF-8"?>
<xliff version="2.0" srcLang="en" trgLang="it">
  <file id="f1">
    <unit id="u1">
      <segment>
        <source>Open</source>
        <target></target>
      </segment>
    </unit>
    <unit id="u2">
      <segment state="needs-l10n">
        <source>Close</source>
        <target>Close</target>
      </segment>
    </unit>
    <unit id="u3">
      <segment>
        <source>Save As</source>
        <target>Salva come</target>
      </segment>
    </unit>
  </file>
</xliff>
"""
        )
        val result2 = scanner.scan(
            mockVirtualFile,
            setOf(
                "new",
                "needs-translation",
                "needs-l10n",
                "needs-adaptation",
                "no-state_target-equals-source",
                "no-state_empty-target"
            ),
            setOf(
                "initial",
                "no-state_target-equals-source",
                "no-state_empty-target"
            )
        )
        assertTrue(result2.hasUntranslatedUnits)
        assertTrue(result2.isXliff2)
        assertTrue(result2.hasTargetLanguageAttribute)
    }

    @Test
    fun `should detect missing target language in XLIFF 1_2`() {
        val mockVirtualFile = MockVirtualFile(
            "missing-target.xlf",
            """<?xml version="1.0" encoding="UTF-8"?>
<xliff version="1.2">
  <file source-language="en">
    <body>
      <trans-unit id="1">
        <source>Hello</source>
        <target>Bonjour</target>
      </trans-unit>
    </body>
  </file>
</xliff>"""
        )
        val result = scanner.scan(mockVirtualFile, emptySet(), emptySet())
        assertFalse(result.hasTargetLanguageAttribute)
        assertFalse(result.isXliff2)
    }

    @Test
    fun `should detect missing target language in XLIFF 2_0`() {
        val mockVirtualFile = MockVirtualFile(
            "missing-target-2.xlf",
            """<?xml version="1.0" encoding="UTF-8"?>
<xliff version="2.0" srcLang="en">
  <file id="f1">
    <unit id="u1">
      <segment>
        <source>Hello</source>
        <target>Bonjour</target>
      </segment>
    </unit>
  </file>
</xliff>"""
        )
        val result = scanner.scan(mockVirtualFile, emptySet(), emptySet())
        assertFalse(result.hasTargetLanguageAttribute)
        assertTrue(result.isXliff2)
    }
}

