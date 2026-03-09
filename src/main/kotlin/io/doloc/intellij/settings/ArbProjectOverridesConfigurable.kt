package io.doloc.intellij.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import io.doloc.intellij.arb.ArbProjectOverridesService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class ArbProjectOverridesConfigurable(
    private val project: Project
) : Configurable, Configurable.NoScroll {
    private val service = ArbProjectOverridesService.getInstance(project)
    private var workingScopes = mutableListOf<ArbProjectOverridesService.ArbScopeOverride>()
    private var component: JComponent? = null
    private var contentPanel: JPanel? = null

    override fun getDisplayName(): String = "ARB Overrides"

    override fun createComponent(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(10)

        contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        panel.add(JBScrollPane(contentPanel), BorderLayout.CENTER)
        component = panel
        reset()
        return panel
    }

    override fun isModified(): Boolean {
        return workingScopes != service.getScopeSnapshot()
    }

    override fun apply() {
        service.replaceScopes(workingScopes)
    }

    override fun reset() {
        workingScopes = service.getScopeSnapshot().toMutableList()
        rebuildContent()
    }

    private fun rebuildContent() {
        val panel = contentPanel ?: return
        panel.removeAll()

        if (workingScopes.isEmpty()) {
            panel.add(JLabel("No ARB overrides have been saved for this project yet."))
        } else {
            workingScopes.sortedBy { it.scopeDir }.forEach { scope ->
                panel.add(createScopePanel(scope))
                panel.add(Box.createVerticalStrut(JBUI.scale(10)))
            }
        }

        panel.revalidate()
        panel.repaint()
    }

    private fun createScopePanel(scope: ArbProjectOverridesService.ArbScopeOverride): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(10)

        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            gridwidth = GridBagConstraints.REMAINDER
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
        }

        panel.add(JLabel("Scope: ${scope.scopeDir}"), gbc)
        gbc.gridy++
        panel.add(JLabel("Base file: ${scope.baseFile.ifBlank { "<none>" }}"), gbc)
        gbc.gridy++
        panel.add(JLabel("Source language: ${scope.sourceLang.ifBlank { "<none>" }}"), gbc)
        gbc.gridy++

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        buttonPanel.add(JButton("Clear scope").apply {
            addActionListener {
                workingScopes.removeIf { it.scopeDir == scope.scopeDir }
                rebuildContent()
            }
        })
        panel.add(buttonPanel, gbc)

        gbc.gridy++
        panel.add(Box.createVerticalStrut(JBUI.scale(8)), gbc)
        gbc.gridy++
        panel.add(JLabel("Target language overrides:"), gbc)

        if (scope.targetOverrides.isEmpty()) {
            gbc.gridy++
            panel.add(JLabel("None"), gbc)
        } else {
            scope.targetOverrides.sortedBy { it.targetFile }.forEach { targetOverride ->
                gbc.gridy++
                panel.add(createTargetPanel(scope, targetOverride), gbc)
            }
        }

        return panel
    }

    private fun createTargetPanel(
        scope: ArbProjectOverridesService.ArbScopeOverride,
        targetOverride: ArbProjectOverridesService.ArbTargetOverride
    ): JComponent {
        val panel = JPanel(BorderLayout(JBUI.scale(8), 0))
        val targetFile = service.resolveStoredFile(targetOverride.targetFile)
        val staleSuffix = if (targetFile == null) " (stale)" else ""
        panel.add(JLabel("${targetOverride.targetFile}$staleSuffix -> ${targetOverride.targetLang}"), BorderLayout.CENTER)
        panel.add(JButton("Clear").apply {
            addActionListener {
                workingScopes.firstOrNull { it.scopeDir == scope.scopeDir }
                    ?.let { workingScope ->
                        workingScope.targetOverrides.removeIf { it.targetFile == targetOverride.targetFile }
                        if (workingScope.baseFile.isBlank() && workingScope.sourceLang.isBlank() && workingScope.targetOverrides.isEmpty()) {
                            workingScopes.removeIf { it.scopeDir == scope.scopeDir }
                        }
                    }
                rebuildContent()
            }
        }, BorderLayout.EAST)
        return panel
    }
}
