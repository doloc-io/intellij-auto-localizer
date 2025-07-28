package io.doloc.intellij.settings

import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Persistent state for doloc plugin settings
 */
@State(
    name = "io.doloc.intellij.settings.DolocSettingsState",
    storages = [Storage("DolocSettings.xml")]
)
class DolocSettingsState : PersistentStateComponent<DolocSettingsState> {

    // Default untranslated states for XLIFF 1.2
    val defaultXliff12UntranslatedStates = setOf(
        "new",
        "needs-translation",
        "needs-l10n",
        "needs-adaptation",
        "no-state_target-equals-source",
        "no-state_empty-target"
    )

    // Default untranslated states for XLIFF 2.0
    val defaultXliff20UntranslatedStates = setOf(
        "initial",
        "no-state_target-equals-source",
        "no-state_empty-target"
    )

    // XLIFF 1.2 specific settings
    var xliff12UntranslatedStates: MutableSet<String> = defaultXliff12UntranslatedStates.toMutableSet()
    var xliff12NewState: String = "translated"

    // XLIFF 2.0 specific settings
    var xliff20UntranslatedStates: MutableSet<String> = defaultXliff20UntranslatedStates.toMutableSet()
    var xliff20NewState: String = "translated"

    // Common settings
    var showReminderToast: Boolean = true
    var useAnonymousToken: Boolean = true

    override fun getState(): DolocSettingsState = this

    override fun loadState(state: DolocSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        @JvmStatic
        fun getInstance(): DolocSettingsState = service()
    }
}
