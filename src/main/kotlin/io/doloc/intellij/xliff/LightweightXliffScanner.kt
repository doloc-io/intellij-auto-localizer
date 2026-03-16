package io.doloc.intellij.xliff

import com.intellij.openapi.vfs.VirtualFile
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import javax.xml.parsers.SAXParserFactory

/**
 * Lightweight scanner for XLIFF files that detects untranslated units
 */
class LightweightXliffScanner {
    data class ScanResult(
        val hasUntranslatedUnits: Boolean,
        val isXliff2: Boolean,
        val targetLanguageAttribute: TargetLanguageAttribute,
        val targetLanguageValue: String?
    )


    /**
     * Scans an XLIFF file to detect untranslated units and determine version
     * @param file The XLIFF file to scan
     * @return [ScanResult] with detection info
     */
    fun scan(
        file: VirtualFile,
        xliff12UntranslatedStates: Set<String>,
        xliff20UntranslatedStates: Set<String>,
    ): ScanResult {
        return try {
            val handler = XliffHandler(xliff12UntranslatedStates, xliff20UntranslatedStates)
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newSAXParser()
            parser.parse(file.contentsToByteArray().inputStream(), handler)
            val attribute = if (handler.isXliff2Detected) {
                TargetLanguageAttribute.XLIFF20_ROOT
            } else {
                TargetLanguageAttribute.XLIFF12_FILE
            }
            ScanResult(
                handler.hasUntranslatedUnits,
                handler.isXliff2Detected,
                attribute,
                handler.targetLanguageValue
            )
        } catch (e: XliffParseException) {
            throw e
        } catch (e: Exception) {
            throw XliffParseException(file.name, e)
        }
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
        private var currentState = null as String?
        var isXliff2Detected = false
        var targetLanguageValue: String? = null


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
            if (qName == "xliff") {
                val version = attributes.getValue("version")
                val trgLangAttr = attributes.getValue(TargetLanguageAttribute.XLIFF20_ROOT.attributeName)
                val detectedXliff2 = version?.startsWith("2.") == true || !trgLangAttr.isNullOrBlank()
                if (detectedXliff2) {
                    isXliff2Detected = true
                }
                if (!trgLangAttr.isNullOrBlank()) {
                    targetLanguageValue = trgLangAttr
                }
            }

            when (qName) {
                "source" -> {
                    inSource = true
                    currentSourceValue = ""
                }
                "file" -> {
                    if (!isXliff2Detected && targetLanguageValue.isNullOrBlank()) {
                        val attrValue = attributes.getValue(TargetLanguageAttribute.XLIFF12_FILE.attributeName)
                        if (!attrValue.isNullOrBlank()) {
                            targetLanguageValue = attrValue
                        }
                    }
                }
                "target" -> {
                    inTarget = true

                    currentTargetValue = ""

                    // Check state attribute (XLIFF 1.2 style)
                    if (!isXliff2Detected) {
                        currentState = attributes.getValue("state")
                        if (currentState != null && currentState in xliff12UntranslatedStates) {
                            hasUntranslatedUnits = true
                            return
                        }
                    }

                }
                "trans-unit", "unit" -> {
                    // Reset for new unit
                    currentSourceValue = ""
                    currentTargetValue = ""
                }
                 "segment" -> {
                    // Reset for new unit
                    currentSourceValue = ""
                    currentTargetValue = ""
                    // Check state attribute (XLIFF 2.0 style)
                    if (isXliff2Detected) {
                        currentState = attributes.getValue("state")
                        if (currentState != null && currentState in xliff20UntranslatedStates) {
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
                    if (currentState != null) {
                        return
                    }
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
