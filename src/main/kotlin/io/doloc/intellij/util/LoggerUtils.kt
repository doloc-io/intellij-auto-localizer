package io.doloc.intellij.util

import com.intellij.openapi.diagnostic.Logger

/**
 * Returns a logger for the specified class.
 */
inline fun <reified T> logger(): Logger = Logger.getInstance(T::class.java)
