package io.doloc.intellij.filetype

import com.intellij.icons.AllIcons
import com.intellij.json.JsonLanguage
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

class ArbFileType : LanguageFileType(JsonLanguage.INSTANCE) {
    override fun getName(): String = "ARB"
    override fun getDescription(): String = "ARB translation file"
    override fun getDefaultExtension(): String = "arb"
    override fun getIcon(): Icon? = AllIcons.FileTypes.Json
}
