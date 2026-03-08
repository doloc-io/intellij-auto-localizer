package io.doloc.intellij.translation

data class SelectionValidationResult(
    val kind: TranslationKind?,
    val isValid: Boolean,
    val title: String? = null,
    val message: String? = null
)
