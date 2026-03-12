package io.doloc.intellij.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import io.doloc.intellij.arb.ArbProjectOverridesService
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel

class ArbProjectOverridesConfigurable(
    private val project: Project
) : Configurable, Configurable.NoScroll {
    private val service = ArbProjectOverridesService.getInstance(project)

    private var workingScopes = mutableListOf<ArbProjectOverridesService.ArbScopeOverride>()
    private var listsConfigured = false

    private val scopeListModel = DefaultListModel<ArbProjectOverridesService.ArbScopeOverride>()
    private val scopeList = JBList(scopeListModel)
    private val targetListModel = DefaultListModel<ArbProjectOverridesService.ArbTargetOverride>()
    private val targetList = JBList(targetListModel)

    private lateinit var emptyStatePanel: JPanel
    private lateinit var emptyStateLabel: JLabel
    private lateinit var detailLayout: CardLayout
    private lateinit var detailPanel: JPanel
    private lateinit var scopePathValue: JLabel
    private lateinit var baseFileValue: JLabel
    private lateinit var sourceLangValue: JLabel
    private lateinit var targetSummaryValue: JLabel
    private lateinit var statusValue: JLabel
    private lateinit var addScopeButton: JButton
    private lateinit var addTargetButton: JButton
    private lateinit var editTargetButton: JButton
    private lateinit var removeTargetButton: JButton

    override fun getDisplayName(): String = "ARB Overrides"

    override fun createComponent(): JComponent {
        if (!listsConfigured) {
            configureLists()
            listsConfigured = true
        }

        val root = JPanel(BorderLayout(0, JBUI.scale(10))).apply {
            border = JBUI.Borders.empty(10)
            add(createIntroPanel(), BorderLayout.NORTH)
            add(createSplitter(), BorderLayout.CENTER)
        }

        reset()
        return root
    }

    override fun isModified(): Boolean = workingScopes != service.getScopeSnapshot()

    override fun apply() {
        service.replaceScopes(workingScopes)
    }

    override fun reset() {
        workingScopes = service.getScopeSnapshot().toMutableList()
        refreshScopeList()
        updateDetailPanel()
    }

    private fun configureLists() {
        scopeList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        scopeList.cellRenderer = ScopeListRenderer()
        scopeList.emptyText.text = "No saved scopes"
        scopeList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateDetailPanel()
            }
        }
        scopeList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && scopeList.selectedValue != null) {
                    editSelectedScope()
                }
            }
        })

        targetList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        targetList.cellRenderer = TargetListRenderer()
        targetList.emptyText.text = "No target overrides"
        targetList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateActionState()
            }
        }
        targetList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && targetList.selectedValue != null) {
                    editSelectedTarget()
                }
            }
        })
    }

    private fun createIntroPanel(): JComponent {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JBLabel("Saved overrides from remembered or manually added ARB scopes."))
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(JBLabel("Use Add Scope for a new scope, then Add Target Override on the right for that scope's targets."))
        }
    }

    private fun createSplitter(): JComponent {
        val splitter = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, createScopeListPanel(), createDetailContainer())
        splitter.resizeWeight = 0.34
        splitter.border = BorderFactory.createEmptyBorder()
        splitter.dividerSize = JBUI.scale(10)
        return splitter
    }

    private fun createScopeListPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
        panel.minimumSize = Dimension(JBUI.scale(220), JBUI.scale(280))

        panel.add(JBLabel("Scopes"), BorderLayout.NORTH)
        panel.add(JBScrollPane(scopeList), BorderLayout.CENTER)
        addScopeButton = JButton("Add Scope").apply {
            addActionListener { addScope() }
        }
        panel.add(
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(addScopeButton)
            },
            BorderLayout.SOUTH
        )

        return panel
    }

    private fun createDetailContainer(): JComponent {
        detailLayout = CardLayout()
        detailPanel = JPanel(detailLayout)

        emptyStatePanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(16)
            emptyStateLabel = JBLabel("Select a saved scope to inspect or edit its ARB overrides.").apply {
                horizontalAlignment = JLabel.CENTER
            }
            add(
                emptyStateLabel,
                BorderLayout.CENTER
            )
        }

        detailPanel.add(emptyStatePanel, DETAIL_EMPTY_CARD)
        detailPanel.add(createSelectedScopePanel(), DETAIL_SCOPE_CARD)
        return detailPanel
    }

    private fun createSelectedScopePanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(12)))

        val overviewPanel = JPanel(GridBagLayout())
        overviewPanel.border = JBUI.Borders.emptyBottom(4)
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.NORTHWEST
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insetsBottom(6)
        }

        val header = JBLabel("Scope Details")
        overviewPanel.add(header, gbc)

        gbc.gridy++
        scopePathValue = addDetailRow(overviewPanel, gbc, "Scope", "")
        gbc.gridy++
        baseFileValue = addDetailRow(overviewPanel, gbc, "Base file", "")
        gbc.gridy++
        sourceLangValue = addDetailRow(overviewPanel, gbc, "Source language", "")
        gbc.gridy++
        targetSummaryValue = addDetailRow(overviewPanel, gbc, "Targets", "")
        gbc.gridy++
        statusValue = addDetailRow(overviewPanel, gbc, "Status", "")

        gbc.gridy++
        gbc.insets = JBUI.insetsTop(8)
        val scopeButtonPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            add(JButton("Edit Scope Defaults").apply {
                addActionListener { editSelectedScope() }
            })
            add(JButton("Remove Scope").apply {
                addActionListener { removeSelectedScope() }
            })
        }
        overviewPanel.add(scopeButtonPanel, gbc)

        panel.add(overviewPanel, BorderLayout.NORTH)

        val targetsPanel = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(JBLabel("Target Overrides"), BorderLayout.NORTH)
            add(JBScrollPane(targetList), BorderLayout.CENTER)
        }

        val targetButtonPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
        addTargetButton = JButton("Add Target Override").apply {
            addActionListener { addTargetOverride() }
        }
        editTargetButton = JButton("Edit").apply {
            addActionListener { editSelectedTarget() }
        }
        removeTargetButton = JButton("Remove").apply {
            addActionListener { removeSelectedTarget() }
        }
        targetButtonPanel.add(addTargetButton)
        targetButtonPanel.add(editTargetButton)
        targetButtonPanel.add(removeTargetButton)
        targetsPanel.add(targetButtonPanel, BorderLayout.SOUTH)

        panel.add(targetsPanel, BorderLayout.CENTER)

        return panel
    }

    private fun addDetailRow(
        panel: JPanel,
        gbc: GridBagConstraints,
        labelText: String,
        initialValue: String
    ): JLabel {
        val rowPanel = JPanel(BorderLayout(JBUI.scale(8), 0))
        rowPanel.add(JBLabel(labelText), BorderLayout.WEST)
        val valueLabel = JBLabel(initialValue)
        rowPanel.add(valueLabel, BorderLayout.CENTER)
        panel.add(rowPanel, gbc)
        return valueLabel
    }

    private fun refreshScopeList(preferredScopeDir: String? = selectedScopeDir()): Unit {
        val sorted = workingScopes.sortedBy { it.scopeDir.lowercase() }
        scopeListModel.removeAllElements()
        sorted.forEach(scopeListModel::addElement)

        if (scopeListModel.isEmpty) {
            scopeList.clearSelection()
            return
        }

        val selectedIndex = sorted.indexOfFirst { it.scopeDir == preferredScopeDir }
        scopeList.selectedIndex = if (selectedIndex >= 0) selectedIndex else 0
    }

    private fun refreshTargetList(scope: ArbProjectOverridesService.ArbScopeOverride?) {
        targetListModel.removeAllElements()
        scope?.targetOverrides
            ?.sortedBy { it.targetFile.lowercase() }
            ?.forEach(targetListModel::addElement)
    }

    private fun updateDetailPanel() {
        val scope = selectedScope()
        refreshTargetList(scope)

        if (scope == null) {
            val message = if (workingScopes.isEmpty()) {
                "No saved ARB override scopes yet. Add one manually or remember a scope during ARB translation."
            } else {
                "Select a saved scope to inspect or edit its ARB overrides."
            }
            emptyStateLabel.text = message
            detailLayout.show(detailPanel, DETAIL_EMPTY_CARD)
            updateActionState()
            return
        }

        scopePathValue.text = scope.scopeDir.ifBlank { "<project root>" }
        scopePathValue.toolTipText = scope.scopeDir.ifBlank { "<project root>" }
        baseFileValue.text = formatFileValue(scope.baseFile)
        baseFileValue.toolTipText = scope.baseFile.ifBlank { null }
        sourceLangValue.text = scope.sourceLang.ifBlank { "<not set>" }
        sourceLangValue.toolTipText = sourceLangValue.text
        targetSummaryValue.text = formatTargetSummary(scope)
        targetSummaryValue.toolTipText = targetSummaryValue.text
        statusValue.text = formatStatus(scope)
        statusValue.toolTipText = statusValue.text
        detailLayout.show(detailPanel, DETAIL_SCOPE_CARD)
        updateActionState()
    }

    private fun updateActionState() {
        addScopeButton.isEnabled = true
        val hasScope = selectedScope() != null
        addTargetButton.isEnabled = hasScope
        editTargetButton.isEnabled = hasScope && targetList.selectedValue != null
        removeTargetButton.isEnabled = hasScope && targetList.selectedValue != null
    }

    private fun addScope() {
        val dialog = ArbScopeDefaultsDialog(
            project,
            service,
            null,
            workingScopes.map { it.scopeDir }.toSet()
        )
        if (!dialog.showAndGet()) {
            return
        }

        val result = dialog.result ?: return
        workingScopes.add(
            ArbProjectOverridesService.ArbScopeOverride(
                scopeDir = result.scopeDir,
                baseFile = result.baseFile,
                sourceLang = result.sourceLang
            )
        )
        refreshScopeList(result.scopeDir)
        updateDetailPanel()
    }

    private fun editSelectedScope() {
        val scope = selectedScope() ?: return
        val dialog = ArbScopeDefaultsDialog(project, service, scope)
        if (!dialog.showAndGet()) {
            return
        }

        val result = dialog.result ?: return
        scope.baseFile = result.baseFile
        scope.sourceLang = result.sourceLang
        cleanupScopeIfEmpty(scope)
        refreshScopeList(scope.scopeDir)
        updateDetailPanel()
    }

    private fun removeSelectedScope() {
        val scope = selectedScope() ?: return
        val confirmed = Messages.showYesNoDialog(
            project,
            "Remove saved overrides for ${scope.scopeDir.ifBlank { "this scope" }}?",
            "Remove ARB Scope Override",
            Messages.getQuestionIcon()
        ) == Messages.YES
        if (!confirmed) {
            return
        }

        workingScopes.removeIf { it.scopeDir == scope.scopeDir }
        refreshScopeList(scope.scopeDir)
        updateDetailPanel()
    }

    private fun addTargetOverride() {
        val scope = selectedScope() ?: return
        val dialog = ArbTargetOverrideDialog(project, service, null, scope.targetOverrides.map { it.targetFile }.toSet())
        if (!dialog.showAndGet()) {
            return
        }

        val result = dialog.result ?: return
        scope.targetOverrides.add(result)
        refreshScopeList(scope.scopeDir)
        refreshTargetList(scope)
        selectTarget(result.targetFile)
        updateDetailPanel()
    }

    private fun editSelectedTarget() {
        val scope = selectedScope() ?: return
        val targetOverride = selectedTargetOverride() ?: return
        val dialog = ArbTargetOverrideDialog(
            project,
            service,
            targetOverride,
            scope.targetOverrides
                .map { it.targetFile }
                .filterNot { it == targetOverride.targetFile }
                .toSet()
        )
        if (!dialog.showAndGet()) {
            return
        }

        val result = dialog.result ?: return
        targetOverride.targetFile = result.targetFile
        targetOverride.targetLang = result.targetLang
        refreshScopeList(scope.scopeDir)
        refreshTargetList(scope)
        selectTarget(result.targetFile)
        updateDetailPanel()
    }

    private fun removeSelectedTarget() {
        val scope = selectedScope() ?: return
        val targetOverride = selectedTargetOverride() ?: return
        val confirmed = Messages.showYesNoDialog(
            project,
            "Remove the override for ${targetOverride.targetFile}?",
            "Remove Target Override",
            Messages.getQuestionIcon()
        ) == Messages.YES
        if (!confirmed) {
            return
        }

        scope.targetOverrides.removeIf { it.targetFile == targetOverride.targetFile }
        cleanupScopeIfEmpty(scope)
        refreshScopeList(scope.scopeDir)
        updateDetailPanel()
    }

    private fun cleanupScopeIfEmpty(scope: ArbProjectOverridesService.ArbScopeOverride) {
        if (scope.baseFile.isBlank() && scope.sourceLang.isBlank() && scope.targetOverrides.isEmpty()) {
            workingScopes.removeIf { it.scopeDir == scope.scopeDir }
        }
    }

    private fun selectedScope(): ArbProjectOverridesService.ArbScopeOverride? {
        val selectedDir = selectedScopeDir() ?: return null
        return workingScopes.firstOrNull { it.scopeDir == selectedDir }
    }

    private fun selectedScopeDir(): String? = scopeList.selectedValue?.scopeDir

    private fun selectedTargetOverride(): ArbProjectOverridesService.ArbTargetOverride? {
        val selectedScope = selectedScope() ?: return null
        val selectedTargetFile = targetList.selectedValue?.targetFile ?: return null
        return selectedScope.targetOverrides.firstOrNull { it.targetFile == selectedTargetFile }
    }

    private fun selectTarget(targetFile: String) {
        val index = (0 until targetListModel.size).firstOrNull { targetListModel.getElementAt(it).targetFile == targetFile } ?: return
        targetList.selectedIndex = index
    }

    private fun formatFileValue(storedPath: String): String {
        if (storedPath.isBlank()) {
            return "<not set>"
        }
        val staleSuffix = if (service.resolveStoredFile(storedPath) == null) " (missing)" else ""
        return storedPath + staleSuffix
    }

    private fun formatTargetSummary(scope: ArbProjectOverridesService.ArbScopeOverride): String {
        val count = scope.targetOverrides.size
        return if (count == 1) {
            "1 target override"
        } else {
            "$count target overrides"
        }
    }

    private fun formatStatus(scope: ArbProjectOverridesService.ArbScopeOverride): String {
        val issues = buildList {
            if (scope.baseFile.isNotBlank() && service.resolveStoredFile(scope.baseFile) == null) {
                add("Base file is missing")
            }
            val missingTargets = scope.targetOverrides.count { service.resolveStoredFile(it.targetFile) == null }
            if (missingTargets > 0) {
                add(if (missingTargets == 1) "1 target file is missing" else "$missingTargets target files are missing")
            }
        }
        return issues.joinToString("; ").ifBlank { "Ready" }
    }

    private inner class ScopeListRenderer : ColoredListCellRenderer<ArbProjectOverridesService.ArbScopeOverride>() {
        override fun customizeCellRenderer(
            list: JList<out ArbProjectOverridesService.ArbScopeOverride>,
            value: ArbProjectOverridesService.ArbScopeOverride?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            if (value == null) {
                return
            }

            append(value.scopeDir.ifBlank { "<project root>" })
            val details = mutableListOf<String>()
            details += if (value.baseFile.isBlank()) "no base file" else value.baseFile.substringAfterLast('/')
            details += if (value.sourceLang.isBlank()) "no source locale" else value.sourceLang
            details += if (value.targetOverrides.size == 1) "1 target" else "${value.targetOverrides.size} targets"
            append("  ${details.joinToString(" • ")}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    private inner class TargetListRenderer : ColoredListCellRenderer<ArbProjectOverridesService.ArbTargetOverride>() {
        override fun customizeCellRenderer(
            list: JList<out ArbProjectOverridesService.ArbTargetOverride>,
            value: ArbProjectOverridesService.ArbTargetOverride?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            if (value == null) {
                return
            }

            append(value.targetFile)
            val details = buildList {
                add(value.targetLang)
                if (service.resolveStoredFile(value.targetFile) == null) {
                    add("missing file")
                }
            }
            append("  ${details.joinToString(" • ")}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    companion object {
        private const val DETAIL_EMPTY_CARD = "empty"
        private const val DETAIL_SCOPE_CARD = "scope"
    }
}
