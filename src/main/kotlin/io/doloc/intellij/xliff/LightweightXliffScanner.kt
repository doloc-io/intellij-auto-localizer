package io.doloc.intellij.xliff

import com.intellij.openapi.vfs.VirtualFile
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import javax.xml.parsers.SAXParserFactory

/**
 * Lightweight scanner for XLIFF files that detects untranslated units
 */
class LightweightXliffScanner {
    /**
     * Scans an XLIFF file to detect untranslated units
     * @param file The XLIFF file to scan
     * @param useXliff2Settings Force using XLIFF 2.0 settings (otherwise auto-detect)
     * @return true if untranslated units are found, false otherwise
     */
    fun scan(
        file: VirtualFile,
        xliff12UntranslatedStates: Set<String>,
        xliff20UntranslatedStates: Set<String>,
    ): Boolean {
        val handler = XliffHandler(xliff12UntranslatedStates, xliff20UntranslatedStates)
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newSAXParser()
        parser.parse(file.contentsToByteArray().inputStream(), handler)
        return handler.hasUntranslatedUnits
    }

    private class XliffHandler(
        private val xliff12UntranslatedStates: Set<String>,
        private var xliff20UntranslatedStates: Set<String>
    ) : DefaultHandler() {
        var hasUntranslatedUnits = false
        private var inSource = false
        private var inTarget = false
        private var currentSourceValue = ""
        private var currentTargetValue = ""
        private var isXliff2Detected = false

        // Determine which untranslated states to use based on XLIFF version
        private val untranslatedStates: Set<String>
            get() = if (isXliff2Detected) {
                xliff20UntranslatedStates
            } else {
                xliff12UntranslatedStates
            }

        override fun startElement(
            uri: String?,
            localName: String?,
            qName: String,
            attributes: Attributes
        ) {
            // Check XLIFF version
            if (qName == "xliff") {
                val version = attributes.getValue("version")
                isXliff2Detected = version?.startsWith("2.") == true
            }

            when (qName) {
                "source" -> {
                    inSource = true
                    currentSourceValue = ""
                }
                "target" -> {
                    inTarget = true
                    currentTargetValue = ""

                    // Check state attribute (XLIFF 1.2 style)
                    val state = attributes.getValue("state")
                    if (state != null && state in xliff12UntranslatedStates) {
                        hasUntranslatedUnits = true
                        return
                    }
                }
                "trans-unit", "unit", "segment" -> {
                    // Reset for new unit
                    currentSourceValue = ""
                    currentTargetValue = ""

                    // Check state attribute (XLIFF 2.0 style)
                    if (isXliff2Detected) {
                        val state = attributes.getValue("state")
                        if (state != null && state in xliff20UntranslatedStates) {
                            hasUntranslatedUnits = true
                            return
                        }
                    }
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            val value = String(ch, start, length)
            if (inSource) {
                currentSourceValue += value
            } else if (inTarget) {
                currentTargetValue += value
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "source" -> inSource = false
                "target" -> {
                    inTarget = false

                    // Check if target is empty or equals source, using the correct keys for each version
                    if (currentTargetValue.isBlank() && "no-state_empty-target" in untranslatedStates) {
                        hasUntranslatedUnits = true
                    } else if (currentTargetValue.trim() == currentSourceValue.trim() &&
                        "no-state_target-equals-source" in untranslatedStates) {
                        hasUntranslatedUnits = true
                    }
                }
            }
        }
    }
}
