package io.doloc.intellij.filetype

import com.intellij.icons.AllIcons
import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

class XliffFileType : LanguageFileType(XMLLanguage.INSTANCE) {
    override fun getName(): String = "XLIFF"
    override fun getDescription(): String = "XLIFF translation file"
    override fun getDefaultExtension(): String = "xlf"
    override fun getIcon(): Icon? = AllIcons.FileTypes.Xml
}
