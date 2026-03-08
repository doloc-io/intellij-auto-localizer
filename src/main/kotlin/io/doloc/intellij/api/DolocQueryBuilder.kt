package io.doloc.intellij.api

/**
 * Builds query strings for doloc API endpoints
 */
object DolocQueryBuilder {
    private fun appendParam(params: MutableList<String>, key: String, value: String?) {
        if (value.isNullOrBlank()) return
        params.add("$key=$value")
    }
    
    /**
     * Builds a query string for the translate endpoint
     * @param untranslated Optional set of states to consider untranslated
     * @param newState Optional state to set for translated units
     * @return Query string starting with ? or empty string if no parameters
     */
    fun buildTranslateQueryString(
        untranslated: Set<String>? = null,
        newState: String? = null
    ): String {
        val params = mutableListOf<String>()
        
        // Add untranslated states if provided and not default
        untranslated?.let { states ->
            if (states.isNotEmpty()) {
                params.add("untranslated=${states.joinToString(",")}")
            }
        }
        
        // Add new state if provided
        appendParam(params, "newState", newState)

        return if (params.isEmpty()) {
            ""
        } else {
            "?${params.joinToString("&")}"
        }
    }

    fun buildArbTranslateQueryString(
        untranslated: Set<String>,
        sourceLang: String?,
        targetLang: String?
    ): String {
        val params = mutableListOf<String>()
        if (untranslated.isNotEmpty()) {
            params.add("untranslated=${untranslated.joinToString(",")}")
        }
        appendParam(params, "sourceLang", sourceLang)
        appendParam(params, "targetLang", targetLang)

        return if (params.isEmpty()) {
            ""
        } else {
            "?${params.joinToString("&")}"
        }
    }
}
