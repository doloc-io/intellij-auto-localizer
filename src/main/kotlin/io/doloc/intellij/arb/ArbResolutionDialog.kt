package io.doloc.intellij.arb

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

class ArbResolutionDialog(
    project: Project,
    private val scopeDir: VirtualFile,
    private val resolutions: List<ArbPairResolver.ArbTargetResolution>
) : DialogWrapper(project) {
    data class Result(
        val baseFile: VirtualFile,
        val sourceLang: String,
        val rememberScope: Boolean,
        val targetLangs: Map<String, String>,
        val rememberedTargets: Set<String>
    )

    private val baseCandidates = (
        resolutions.flatMap { it.baseCandidates } + ArbHeuristics.collectArbFiles(scopeDir)
    )
        .distinctBy { it.path }
        .sortedBy { it.path }
    private val baseComboBox = JComboBox(baseCandidates.toTypedArray())
    private val sourceLangField = JTextField(20)
    private val rememberScopeCheckBox = JCheckBox("Remember base and source language for this scope")
    private val targetRows = resolutions.mapNotNull { resolution ->
        if (!resolution.needsTargetLangPrompt) return@mapNotNull null
        TargetLangRow(
            resolution.targetFile,
            JTextField(resolution.targetLang.orEmpty(), 16),
            JCheckBox("Remember")
        )
    }

    init {
        title = "Resolve ARB Translation"
        init()

        val initialBase = resolutions.firstNotNullOfOrNull { it.baseFile }
        if (initialBase != null && baseCandidates.isNotEmpty()) {
            baseComboBox.selectedItem = baseCandidates.firstOrNull { it.path == initialBase.path } ?: baseCandidates.first()
        }
        sourceLangField.text = resolutions.firstNotNullOfOrNull { it.sourceLang }.orEmpty()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(10)))
        panel.preferredSize = JBUI.size(560, 320)

        val header = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = Insets(0, 0, JBUI.scale(8), JBUI.scale(8))
        }

        header.add(JLabel("Scope: ${scopeDir.path}"), gbc)
        gbc.gridy++
        header.add(JLabel("Base file:"), gbc)
        gbc.gridy++
        header.add(baseComboBox, gbc)
        gbc.gridy++
        header.add(JLabel("Source language:"), gbc)
        gbc.gridy++
        header.add(sourceLangField, gbc)
        gbc.gridy++
        header.add(rememberScopeCheckBox, gbc)

        panel.add(header, BorderLayout.NORTH)

        val targetsPanel = JPanel()
        targetsPanel.layout = BoxLayout(targetsPanel, BoxLayout.Y_AXIS)
        targetsPanel.border = JBUI.Borders.emptyTop(6)
        if (targetRows.isNotEmpty()) {
            targetRows.forEach { row ->
                val rowPanel = JPanel(GridBagLayout())
                val rowGbc = GridBagConstraints().apply {
                    gridx = 0
                    gridy = 0
                    anchor = GridBagConstraints.WEST
                    fill = GridBagConstraints.HORIZONTAL
                    weightx = 1.0
                    insets = Insets(0, 0, JBUI.scale(6), JBUI.scale(8))
                }
                rowPanel.add(JLabel(row.targetFile.path), rowGbc)
                rowGbc.gridy++
                rowPanel.add(JLabel("Target language:"), rowGbc)
                rowGbc.gridy++
                rowPanel.add(row.field, rowGbc)
                rowGbc.gridy++
                rowPanel.add(row.rememberCheckBox, rowGbc)
                targetsPanel.add(rowPanel)
                targetsPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
            }
        } else {
            targetsPanel.add(JLabel("All target languages were inferred automatically."))
        }

        panel.add(JBScrollPane(targetsPanel), BorderLayout.CENTER)
        return panel
    }

    override fun doValidateAll(): List<ValidationInfo> {
        val errors = mutableListOf<ValidationInfo>()
        if (selectedBaseFile == null) {
            errors += ValidationInfo("Select a base file.", baseComboBox)
        }
        if (ArbLocaleHelper.normalizeLocale(sourceLangField.text).isNullOrBlank()) {
            errors += ValidationInfo("Enter a valid source language.", sourceLangField)
        }
        targetRows.forEach { row ->
            if (ArbLocaleHelper.normalizeLocale(row.field.text).isNullOrBlank()) {
                errors += ValidationInfo("Enter a valid target language for ${row.targetFile.name}.", row.field)
            }
        }
        return errors
    }

    val result: Result?
        get() {
            val baseFile = selectedBaseFile ?: return null
            val sourceLang = ArbLocaleHelper.normalizeLocale(sourceLangField.text) ?: return null
            val targetLangs = mutableMapOf<String, String>()
            resolutions.forEach { resolution ->
                val row = targetRows.firstOrNull { it.targetFile.path == resolution.targetFile.path }
                val targetLang = row?.field?.text?.let(ArbLocaleHelper::normalizeLocale)
                    ?: resolution.targetLang?.let(ArbLocaleHelper::normalizeLocale)
                    ?: return null
                targetLangs[resolution.targetFile.path] = targetLang
            }

            return Result(
                baseFile = baseFile,
                sourceLang = sourceLang,
                rememberScope = rememberScopeCheckBox.isSelected,
                targetLangs = targetLangs,
                rememberedTargets = targetRows
                    .filter { it.rememberCheckBox.isSelected }
                    .map { it.targetFile.path }
                    .toSet()
            )
        }

    private val selectedBaseFile: VirtualFile?
        get() = baseComboBox.selectedItem as? VirtualFile

    private data class TargetLangRow(
        val targetFile: VirtualFile,
        val field: JTextField,
        val rememberCheckBox: JCheckBox
    )
}
