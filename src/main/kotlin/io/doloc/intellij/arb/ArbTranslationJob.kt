package io.doloc.intellij.arb

import com.intellij.openapi.vfs.VirtualFile

data class ArbTranslationJob(
    val scopeDir: VirtualFile,
    val baseFile: VirtualFile,
    val targetFile: VirtualFile,
    val sourceLang: String,
    val targetLang: String
)
