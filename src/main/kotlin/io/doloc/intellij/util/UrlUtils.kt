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
    val parts = base.split('#', limit = 2)
    val path = parts[0]
    val fragment = parts.getOrNull(1)

    val separator = if (path.contains('?')) "&" else "?"
    val urlWithParams = "$path${separator}$BASE_UTM&utm_content=$content"

    return if (fragment != null) "$urlWithParams#$fragment" else urlWithParams
}
