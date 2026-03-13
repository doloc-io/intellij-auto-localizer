package io.doloc.intellij.service

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.ProjectManager
import io.doloc.intellij.auth.AnonymousTokenManager
import io.doloc.intellij.settings.DolocSettingsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

@Service(Level.APP)
class DolocSettingsService {

    private val logger = Logger.getInstance(DolocSettingsService::class.java)
    private val manualTokenKey = "io.doloc.intellij.apiToken"
    private val anonymousTokenKey = "io.doloc.intellij.anonymousApiToken"
    private val _tokenFlow = MutableStateFlow<String?>(null)
    private val tokenStore: TokenStore = tokenStoreFactory()
    @Volatile
    private var tokenStoreAvailable = true
    @Volatile
    private var tokenStoreWarningShown = false
    private val anonymousTokenLock = Any()
    private var manualTokenFallback: String? = null
    private var anonymousTokenFallback: String? = null

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
        synchronized(anonymousTokenLock) {
            val stored = getStoredAnonymousToken()
            if (!stored.isNullOrBlank()) {
                return stored
            }

            return requestAnonymousToken { anonymousTokenManager.createToken() }
        }
    }

    fun refreshAnonymousToken(failedToken: String? = null): String? {
        synchronized(anonymousTokenLock) {
            val currentToken = getStoredAnonymousToken()
            if (
                !failedToken.isNullOrBlank() &&
                !currentToken.isNullOrBlank() &&
                currentToken != failedToken
            ) {
                return currentToken
            }

            clearAnonymousToken()
            return requestAnonymousToken { anonymousTokenManager.createToken() }
        }
    }

    private fun requestAnonymousToken(request: suspend () -> String): String? {
        return runBlocking {
            try {
                request()
            } catch (e: ProcessCanceledException) {
                throw e
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
        return readToken(manualTokenKey, manualTokenFallback) { manualTokenFallback = it }
    }

    fun getStoredAnonymousToken(): String? {
        return readToken(anonymousTokenKey, anonymousTokenFallback) { anonymousTokenFallback = it }
    }

    /**
     * Stores the manual API token in the Password Safe and updates the token flow
     */
    fun setApiToken(token: String?) {
        if (token == null) {
            clearApiToken()
            return
        }

        writeToken(manualTokenKey, token) { manualTokenFallback = it }
        _tokenFlow.value = token
    }

    fun setAnonymousToken(token: String?) {
        if (token == null) {
            clearAnonymousToken()
            return
        }
        writeToken(anonymousTokenKey, token) { anonymousTokenFallback = it }
    }

    /**
     * Clears the stored manual API token
     */
    fun clearApiToken() {
        writeToken(manualTokenKey, null) { manualTokenFallback = it }
        _tokenFlow.value = null
    }

    fun clearAnonymousToken() {
        writeToken(anonymousTokenKey, null) { anonymousTokenFallback = it }
    }

    private fun readToken(key: String, fallback: String?, updateFallback: (String?) -> Unit): String? {
        if (!tokenStoreAvailable) {
            return fallback
        }

        return try {
            tokenStore.read(key).also(updateFallback)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (t: Throwable) {
            disableTokenStore(t)
            fallback
        }
    }

    private fun writeToken(key: String, token: String?, updateFallback: (String?) -> Unit) {
        updateFallback(token)
        if (!tokenStoreAvailable) {
            return
        }

        try {
            tokenStore.write(key, token)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (t: Throwable) {
            disableTokenStore(t)
        }
    }

    private fun disableTokenStore(t: Throwable) {
        if (!tokenStoreAvailable) {
            return
        }

        tokenStoreAvailable = false
        logger.warn(
            "Password Safe is unavailable; falling back to in-memory token storage for this IDE session.",
            t
        )
        notifyTokenStoreUnavailable(t)
    }

    private fun notifyTokenStoreUnavailable(t: Throwable) {
        if (tokenStoreWarningShown) {
            return
        }

        tokenStoreWarningShown = true
        tokenStoreFailureNotifier(t)
    }

    companion object {
        internal var tokenStoreFactory: () -> TokenStore = { PasswordSafeTokenStore() }
        internal var tokenStoreFailureNotifier: (Throwable) -> Unit = { PasswordSafeUnavailableNotifier.notify(it) }

        internal fun resetTokenStoreFactory() {
            tokenStoreFactory = { PasswordSafeTokenStore() }
        }

        internal fun resetTokenStoreFailureNotifier() {
            tokenStoreFailureNotifier = { PasswordSafeUnavailableNotifier.notify(it) }
        }

        @JvmStatic
        fun getInstance(): DolocSettingsService {
            return ApplicationManager.getApplication().getService(DolocSettingsService::class.java)
        }
    }
}

internal interface TokenStore {
    fun read(key: String): String?
    fun write(key: String, token: String?)
}

private class PasswordSafeTokenStore : TokenStore {
    override fun read(key: String): String? {
        val credentials = PasswordSafe.instance.get(createCredentialAttributes(key))
        return credentials?.getPasswordAsString()
    }

    override fun write(key: String, token: String?) {
        val credentials = token?.let { Credentials(key, it) }
        PasswordSafe.instance.set(createCredentialAttributes(key), credentials)
    }

    private fun createCredentialAttributes(key: String): CredentialAttributes {
        return CredentialAttributes(key)
    }
}

private data class PasswordSafeUnavailableMessage(
    val title: String,
    val content: String
)

private object PasswordSafeUnavailableNotifier {
    private const val PASSWORD_SETTINGS_NAME = "Passwords"

    fun notify(cause: Throwable) {
        val message = buildMessage(cause)
        ApplicationManager.getApplication().invokeLater {
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("Doloc Translation")
                .createNotification(message.title, message.content, NotificationType.WARNING)
                .addAction(object : AnAction("Open Password Settings") {
                    override fun actionPerformed(e: AnActionEvent) {
                        val project = e.project ?: ProjectManager.getInstance().openProjects.firstOrNull()
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, PASSWORD_SETTINGS_NAME)
                    }
                })

            notification.notify(ProjectManager.getInstance().openProjects.firstOrNull())
        }
    }

    private fun buildMessage(cause: Throwable): PasswordSafeUnavailableMessage {
        return if (hasMessage(cause, "libsecret-1")) {
            PasswordSafeUnavailableMessage(
                title = "Linux password storage is unavailable",
                content = "Auto Localizer cannot use IntelliJ Password Safe because <code>libsecret-1</code> is missing. Install <code>libsecret-1-0</code> on Debian/Ubuntu or <code>libsecret</code> on Fedora/Arch, or switch IntelliJ password storage to KeePass / In Memory in Settings | Appearance &amp; Behavior | System Settings | Passwords. Tokens are kept only for this IDE session."
            )
        } else {
            PasswordSafeUnavailableMessage(
                title = "Password storage is unavailable",
                content = "Auto Localizer cannot use IntelliJ Password Safe. Switch IntelliJ password storage to KeePass / In Memory in Settings | Appearance &amp; Behavior | System Settings | Passwords. Tokens are kept only for this IDE session."
            )
        }
    }

    private fun hasMessage(throwable: Throwable?, fragment: String): Boolean {
        var current = throwable
        while (current != null) {
            if (current.message?.contains(fragment, ignoreCase = true) == true) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
