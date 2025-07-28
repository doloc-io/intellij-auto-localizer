package io.doloc.intellij.service

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.diagnostic.Logger
import io.doloc.intellij.auth.AnonymousTokenManager
import io.doloc.intellij.settings.DolocSettingsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

@Service(Level.APP)
class DolocSettingsService {

    private val logger = Logger.getInstance(DolocSettingsService::class.java)
    // Keep the legacy key so existing users don't have to re-enter their token
    private val manualTokenKey = "io.doloc.intellij.apiToken"
    private val anonymousTokenKey = "io.doloc.intellij.anonymousToken"
    private val _tokenFlow = MutableStateFlow<String?>(null) // manual token flow

    // Lazy-initialized token manager
    private val anonymousTokenManager by lazy { AnonymousTokenManager(this) }

    val tokenFlow: StateFlow<String?>
        get() = _tokenFlow.asStateFlow()

    init {
        _tokenFlow.value = getStoredManualToken()
    }

    /**
     * Returns the API token according to user preference. If manual token is selected
     * this will return the stored manual token. Otherwise the anonymous token is returned
     * and created lazily on first request.
     */
    fun getApiToken(): String? {
        val state = DolocSettingsState.getInstance()
        return if (state.useAnonymousToken) {
            getOrCreateAnonymousToken()
        } else {
            getStoredManualToken()
        }
    }

    private fun getOrCreateAnonymousToken(): String? {
        val stored = getStoredAnonymousToken()
        if (!stored.isNullOrBlank()) return stored

        return runBlocking {
            try {
                anonymousTokenManager.getOrCreateToken()
            } catch (e: Exception) {
                logger.warn("Failed to get anonymous token", e)
                null
            }
        }
    }

    /**
     * Returns the stored token without attempting to create an anonymous one
     */
    fun getStoredManualToken(): String? {
        val credentials = PasswordSafe.instance.get(createCredentialAttributes(manualTokenKey))
        return credentials?.getPasswordAsString()
    }

    fun getStoredAnonymousToken(): String? {
        val credentials = PasswordSafe.instance.get(createCredentialAttributes(anonymousTokenKey))
        return credentials?.getPasswordAsString()
    }

    /**
     * Stores the manual API token in the Password Safe and updates the token flow
     */
    fun setApiToken(token: String?) {
        if (token == null) {
            clearApiToken()
            return
        }

        val credentials = Credentials(manualTokenKey, token)
        PasswordSafe.instance.set(createCredentialAttributes(manualTokenKey), credentials)
        _tokenFlow.value = token
    }

    fun setAnonymousToken(token: String?) {
        if (token == null) {
            clearAnonymousToken()
            return
        }
        val credentials = Credentials(anonymousTokenKey, token)
        PasswordSafe.instance.set(createCredentialAttributes(anonymousTokenKey), credentials)
    }

    /**
     * Clears the stored manual API token
     */
    fun clearApiToken() {
        PasswordSafe.instance.set(createCredentialAttributes(manualTokenKey), null)
        _tokenFlow.value = null
    }

    fun clearAnonymousToken() {
        PasswordSafe.instance.set(createCredentialAttributes(anonymousTokenKey), null)
    }

    private fun createCredentialAttributes(key: String): CredentialAttributes {
        return CredentialAttributes(key) // TODO constructor deprecated
    }

    companion object {
        @JvmStatic
        fun getInstance(): DolocSettingsService {
            return ApplicationManager.getApplication().getService(DolocSettingsService::class.java)
        }
    }
}
