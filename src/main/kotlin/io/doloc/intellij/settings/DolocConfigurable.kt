package io.doloc.intellij.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.util.ui.JBUI
import io.doloc.intellij.service.DolocSettingsService
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import javax.swing.*

/**
 * Settings configuration panel for doloc plugin (redesigned layout)
 */
class DolocConfigurable : Configurable {
    private val settingsService = DolocSettingsService.getInstance()
    private lateinit var tokenFieldComponent: JPasswordField
    private lateinit var xliff12UntranslatedCheckboxes: Map<String, JCheckBox>
    private lateinit var xliff12NewStateRadioButtons: Map<String, JRadioButton>
    private lateinit var xliff20UntranslatedCheckboxes: Map<String, JCheckBox>
    private lateinit var xliff20NewStateRadioButtons: Map<String, JRadioButton>
    private lateinit var showReminderCheckbox: JCheckBox

    override fun getDisplayName(): String = "Auto Localizer"

    override fun createComponent(): JComponent {
        // Main panel with vertical box layout
        val mainPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            gridwidth = GridBagConstraints.REMAINDER
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
        }

        // 1. Token Section
        mainPanel.add(createTokenSection(), gbc)

        gbc.gridy++
        mainPanel.add(Box.createVerticalStrut(10), gbc)

        // 2. Reminder Toast Section
        gbc.gridy++
        mainPanel.add(createReminderSection(), gbc)

        gbc.gridy++
        mainPanel.add(Box.createVerticalStrut(10), gbc)

        // 3. XLIFF 1.2 Settings Section
        val settingsState = DolocSettingsState.getInstance()
        val (xliff12Panel, xliff12Checkboxes, xliff12Radios) = createXliffSettingsSection(
            "XLIFF 1.2",
            "https://doloc.io/getting-started/formats/xliff-1-2/",
            getXliff12UntranslatedStates(),
            getXliff12NewStateOptions(),
            settingsState.xliff12UntranslatedStates,
            settingsState.xliff12NewState
        )
        xliff12UntranslatedCheckboxes = xliff12Checkboxes
        xliff12NewStateRadioButtons = xliff12Radios

        gbc.gridy++
        mainPanel.add(xliff12Panel, gbc)

        gbc.gridy++
        mainPanel.add(Box.createVerticalStrut(10), gbc)

        // 4. XLIFF 2.0 Settings Section
        val (xliff20Panel, xliff20Checkboxes, xliff20Radios) = createXliffSettingsSection(
            "XLIFF 2.0",
            "https://doloc.io/getting-started/formats/xliff-2-0/",
            getXliff20UntranslatedStates(),
            getXliff20NewStateOptions(),
            settingsState.xliff20UntranslatedStates,
            settingsState.xliff20NewState
        )
        xliff20UntranslatedCheckboxes = xliff20Checkboxes
        xliff20NewStateRadioButtons = xliff20Radios

        gbc.gridy++
        mainPanel.add(xliff20Panel, gbc)

        // Add vertical glue to push everything to the top
        gbc.gridy++
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        mainPanel.add(Box.createVerticalGlue(), gbc)

        mainPanel.border = JBUI.Borders.empty(10)
        return mainPanel
    }

    private fun createSectionHeader(title: String): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)

        val label = JLabel(title)
        label.alignmentY = 0.5f
        panel.add(label)
        panel.add(Box.createHorizontalStrut(10))

        val separator = JSeparator()
        separator.alignmentY = 0.5f
        separator.maximumSize = java.awt.Dimension(Int.MAX_VALUE, separator.preferredSize.height)
        panel.add(separator)

        return panel
    }

    private fun createTokenSection(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            gridwidth = GridBagConstraints.REMAINDER
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
        }

        // Section title with separator
        panel.add(createSectionHeader("API Token"), gbc)

        // Content panel with indentation
        gbc.gridy++
        gbc.insets = JBUI.insets(5, 20, 0, 0) // Indentation

        // Token field panel
        val tokenPanel = JPanel(BorderLayout())
        tokenFieldComponent = JPasswordField().apply {
            columns = 30
            settingsService.getStoredToken()?.let { text = it }
        }
        tokenPanel.add(tokenFieldComponent, BorderLayout.CENTER)

        // Remove token button
        val removeButton = JButton("Remove").apply {
            addActionListener {
                tokenFieldComponent.text = ""
            }
        }
        tokenPanel.add(removeButton, BorderLayout.EAST)
        panel.add(tokenPanel, gbc)

        // Add hyperlink
        gbc.gridy++
        val linkLabel = LinkLabel<Any?>("Get your token on doloc.io/account", null).apply {
            setListener({ _, _ ->
                BrowserUtil.browse("https://doloc.io/account")
            }, null)
        }
        panel.add(linkLabel, gbc)

        // (Optional) Add free-tier status if applicable

        return panel
    }

    private fun createReminderSection(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            gridwidth = GridBagConstraints.REMAINDER
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
        }

        // Section title with separator
        panel.add(createSectionHeader("Reminder Settings"), gbc)

        // Content panel with indentation
        gbc.gridy++
        gbc.insets = JBUI.insets(5, 20, 0, 0) // Indentation

        showReminderCheckbox = JCheckBox("Show reminder toast after save").apply {
            isSelected = DolocSettingsState.getInstance().showReminderToast
        }
        panel.add(showReminderCheckbox, gbc)

        return panel
    }

    // Helper for XLIFF settings section
    private fun createXliffSettingsSection(
        title: String,
        docsUrl: String,
        untranslatedStates: List<Pair<String, String>>,
        newStateOptions: List<Pair<String, String>>,
        currentUntranslatedStates: Set<String>,
        currentNewState: String
    ): Triple<JComponent, Map<String, JCheckBox>, Map<String, JRadioButton>> {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            gridwidth = GridBagConstraints.REMAINDER
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
        }

        // Section title with separator
        panel.add(createSectionHeader(title), gbc)

        // Documentation link
        gbc.gridy++
        gbc.insets = JBUI.insets(3, 20, 5, 0)
        val linkLabel = LinkLabel<Any?>("Learn more about $title options", null).apply {
            setListener({ _, _ -> BrowserUtil.browse(docsUrl) }, null)
        }
        panel.add(linkLabel, gbc)

        // Untranslated states
        gbc.gridy++
        gbc.insets = JBUI.insets(5, 20, 0, 0)
        panel.add(JLabel("Untranslated states:"), gbc)

        // Create checkboxes
        val checkboxPanel = JPanel(GridLayout(0, 1, 0, 0))
        val checkboxMap = untranslatedStates.associate { (value, label) ->
            val checkbox = JCheckBox(label)
            checkbox.isSelected = currentUntranslatedStates.contains(value)
            checkboxPanel.add(checkbox)
            value to checkbox
        }

        gbc.gridy++
        panel.add(checkboxPanel, gbc)

        // New state options
        gbc.gridy++
        gbc.insets = JBUI.insets(15, 20, 0, 0)
        panel.add(JLabel("New state:"), gbc)

        // Create radio buttons
        val radioPanel = JPanel(GridLayout(0, 1, 0, 0))
        val radioGroup = ButtonGroup()
        val radioMap = newStateOptions.associate { (value, label) ->
            val radio = JRadioButton(label)
            radioGroup.add(radio)
            radioPanel.add(radio)
            radio.isSelected = value == currentNewState
            value to radio
        }

        gbc.gridy++
        panel.add(radioPanel, gbc)

        return Triple(panel, checkboxMap, radioMap)
    }

    private fun getXliff12UntranslatedStates(): List<Pair<String, String>> {
        return listOf(
            "new" to "new",
            "needs-translation" to "needs-translation",
            "needs-l10n" to "needs-l10n",
            "needs-adaptation" to "needs-adaptation",
            "needs-review-translation" to "needs-review-translation",
            "needs-review-l10n" to "needs-review-l10n",
            "needs-review-adaptation" to "needs-review-adaptation",
            "translated" to "translated",
            "signed-off" to "signed-off",
            "no-state_target-equals-source" to "No State: Target equals Source",
            "no-state_empty-target" to "No State: Empty Target"
        )
    }

    private fun getXliff12NewStateOptions(): List<Pair<String, String>> {
        return listOf(
            "new" to "new",
            "needs-translation" to "needs-translation",
            "needs-l10n" to "needs-l10n",
            "needs-adaptation" to "needs-adaptation",
            "needs-review-translation" to "needs-review-translation",
            "needs-review-l10n" to "needs-review-l10n",
            "needs-review-adaptation" to "needs-review-adaptation",
            "translated" to "translated",
            "signed-off" to "signed-off",
            "unchanged" to "Unchanged (do not change state)"
        )
    }

    private fun getXliff20UntranslatedStates(): List<Pair<String, String>> {
        return listOf(
            "initial" to "initial",
            "translated" to "translated",
            "reviewed" to "reviewed",
            "final" to "final",
            "no-state_target-equals-source" to "No State: Target equals Source",
            "no-state_empty-target" to "No State: Empty Target"
        )
    }

    private fun getXliff20NewStateOptions(): List<Pair<String, String>> {
        return listOf(
            "initial" to "initial",
            "translated" to "translated",
            "reviewed" to "reviewed",
            "final" to "final",
            "unchanged" to "Unchanged (do not change state)"
        )
    }

    override fun isModified(): Boolean {
        val settingsState = DolocSettingsState.getInstance()

        // Check if token changed
        val currentToken = String(tokenFieldComponent.password)
        val storedToken = settingsService.getStoredToken() ?: ""

        // Check if XLIFF 1.2 untranslated states changed
        val selectedXliff12Untranslated = xliff12UntranslatedCheckboxes
            .filter { (_, checkbox) -> checkbox.isSelected }
            .map { (value, _) -> value }
            .toSet()

        // Check if XLIFF 2.0 untranslated states changed
        val selectedXliff20Untranslated = xliff20UntranslatedCheckboxes
            .filter { (_, checkbox) -> checkbox.isSelected }
            .map { (value, _) -> value }
            .toSet()

        // Check if new states changed
        val selectedXliff12NewState = xliff12NewStateRadioButtons
            .firstNotNullOfOrNull { (value, radio) -> if (radio.isSelected) value else null }

        val selectedXliff20NewState = xliff20NewStateRadioButtons
            .firstNotNullOfOrNull { (value, radio) -> if (radio.isSelected) value else null }

        return currentToken != storedToken ||
               selectedXliff12Untranslated != settingsState.xliff12UntranslatedStates ||
               selectedXliff20Untranslated != settingsState.xliff20UntranslatedStates ||
               selectedXliff12NewState != settingsState.xliff12NewState ||
               selectedXliff20NewState != settingsState.xliff20NewState ||
               showReminderCheckbox.isSelected != settingsState.showReminderToast
    }

    override fun apply() {
        val settingsState = DolocSettingsState.getInstance()

        // Save token
        val token = String(tokenFieldComponent.password)
        if (token.isNotBlank()) {
            settingsService.setApiToken(token)
        } else {
            settingsService.clearApiToken()
        }

        // Save XLIFF 1.2 untranslated states
        settingsState.xliff12UntranslatedStates = xliff12UntranslatedCheckboxes
            .filter { (_, checkbox) -> checkbox.isSelected }
            .map { (value, _) -> value }
            .toMutableSet()

        // Save XLIFF 2.0 untranslated states
        settingsState.xliff20UntranslatedStates = xliff20UntranslatedCheckboxes
            .filter { (_, checkbox) -> checkbox.isSelected }
            .map { (value, _) -> value }
            .toMutableSet()

        // Save new states
        xliff12NewStateRadioButtons
            .firstNotNullOfOrNull { (value, radio) -> if (radio.isSelected) value else null }
            ?.let { settingsState.xliff12NewState = it }

        xliff20NewStateRadioButtons
            .firstNotNullOfOrNull { (value, radio) -> if (radio.isSelected) value else null }
            ?.let { settingsState.xliff20NewState = it }

        // Save reminder toast setting
        settingsState.showReminderToast = showReminderCheckbox.isSelected
    }

    override fun reset() {
        val settingsState = DolocSettingsState.getInstance()

        // Reset token
        tokenFieldComponent.text = settingsService.getStoredToken() ?: ""

        // Reset XLIFF 1.2 untranslated states
        xliff12UntranslatedCheckboxes.forEach { (value, checkbox) ->
            checkbox.isSelected = settingsState.xliff12UntranslatedStates.contains(value)
        }

        // Reset XLIFF 2.0 untranslated states
        xliff20UntranslatedCheckboxes.forEach { (value, checkbox) ->
            checkbox.isSelected = settingsState.xliff20UntranslatedStates.contains(value)
        }

        // Reset new states
        val xliff12NewState = settingsState.xliff12NewState
        xliff12NewStateRadioButtons.forEach { (value, radio) ->
            radio.isSelected = value == xliff12NewState
        }

        val xliff20NewState = settingsState.xliff20NewState
        xliff20NewStateRadioButtons.forEach { (value, radio) ->
            radio.isSelected = value == xliff20NewState
        }

        // Reset reminder toast
        showReminderCheckbox.isSelected = settingsState.showReminderToast
    }
}
