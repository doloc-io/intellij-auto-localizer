package io.doloc.intellij.api

/**
 * Builds query strings for doloc API endpoints
 */
object DolocQueryBuilder {
    
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
        newState?.let {
            if (it.isNotEmpty()) {
                params.add("newState=$it")
            }
        }
        
        return if (params.isEmpty()) {
            ""
        } else {
            "?${params.joinToString("&")}"
        }
    }
}
