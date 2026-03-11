package io.doloc.intellij.arb

class ArbParseException(
    val fileName: String,
    cause: Throwable
) : IllegalStateException("Failed to parse ARB file $fileName", cause) {
    fun toUserMessage(): String {
        val details = cause?.message
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

        return buildString {
            append("Could not parse ARB file \"")
            append(fileName)
            append("\". Fix the file and try again.")
            if (details.isNotBlank()) {
                append("\n\n")
                append(details)
            }
        }
    }
}
