package io.doloc.intellij.util

/** Base UTM parameters appended to all doloc.io links. */
const val BASE_UTM: String = "utm_source=intellij&utm_medium=plugin&utm_campaign=auto_localizer"

/**
 * Returns the given [base] URL with UTM tracking parameters.
 *
 * @param base    the base URL
 * @param content the utm_content value
 */
fun utmUrl(base: String, content: String): String {
    val separator = if (base.contains("?")) "&" else "?"
    return "$base${separator}$BASE_UTM&utm_content=$content"
}
