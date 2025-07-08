package io.doloc.intellij.service

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.diagnostic.Logger
import io.doloc.intellij.auth.AnonymousTokenManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

@Service(Level.APP)
class DolocSettingsService {

    private val logger = Logger.getInstance(DolocSettingsService::class.java)
    private val tokenCredentialKey = "io.doloc.intellij.apiToken"
    private val _tokenFlow = MutableStateFlow<String?>(null)

    // Lazy-initialized token manager
    private val anonymousTokenManager by lazy { AnonymousTokenManager(this) }

    val tokenFlow: StateFlow<String?>
        get() = _tokenFlow.asStateFlow()

    init {
        _tokenFlow.value = getStoredToken()
    }

    /**
     * Returns the stored API token or attempts to create an anonymous one if not set
     */
    fun getApiToken(): String? {
        val storedToken = getStoredToken()
        if (!storedToken.isNullOrBlank()) {
            return storedToken
        }
        return null;

//        // If no token found, try to get an anonymous one
//        return runBlocking {
//            try {
//                anonymousTokenManager.getOrCreateToken()
//            } catch (e: Exception) {
//                // Log and return null on failure
//                logger.warn("Failed to get anonymous token", e)
//                null
//            }
//        }
    }

    /**
     * Returns the stored token without attempting to create an anonymous one
     */
    fun getStoredToken(): String? {
        val credentials = PasswordSafe.instance.get(createCredentialAttributes())
        return credentials?.getPasswordAsString()
    }

    /**
     * Stores the API token in the Password Safe and updates the token flow
     */
    fun setApiToken(token: String?) {
        if (token == null) {
            clearApiToken()
            return
        }

        val credentials = Credentials(tokenCredentialKey, token)
        PasswordSafe.instance.set(createCredentialAttributes(), credentials)
        _tokenFlow.value = token
    }

    /**
     * Clears the stored API token
     */
    fun clearApiToken() {
        PasswordSafe.instance.set(createCredentialAttributes(), null)
        _tokenFlow.value = null
    }

    private fun createCredentialAttributes(): CredentialAttributes {
        return CredentialAttributes(tokenCredentialKey) // TODO constructor deprecated
    }

    companion object {
        @JvmStatic
        fun getInstance(): DolocSettingsService {
            return ApplicationManager.getApplication().getService(DolocSettingsService::class.java)
        }
    }
}
