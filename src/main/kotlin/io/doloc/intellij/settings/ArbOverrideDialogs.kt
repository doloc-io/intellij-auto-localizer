package io.doloc.intellij.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.doloc.intellij.arb.ArbLocaleHelper
import io.doloc.intellij.arb.ArbProjectOverridesService
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField

class ArbScopeDefaultsDialog(
    private val project: Project,
    private val service: ArbProjectOverridesService,
    private val scope: ArbProjectOverridesService.ArbScopeOverride?,
    private val existingScopeDirs: Set<String> = emptySet()
) : DialogWrapper(project) {
    data class Result(
        val scopeDir: String,
        val baseFile: String,
        val sourceLang: String
    )

    private val isNewScope = scope == null
    private val scopeDirField = createScopeDirectoryField(project, service, "Select Scope Directory")
    private val baseFileField = createArbFileField(project, service, "Select Base ARB File")
    private val sourceLangField = JTextField(scope?.sourceLang.orEmpty(), 16)

    init {
        title = if (isNewScope) "Add ARB Override Scope" else "Edit ARB Scope Defaults"
        baseFileField.text = scope?.baseFile.orEmpty()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(10)))
        panel.preferredSize = JBUI.size(680, 260)

        val content = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.NORTHWEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = JBUI.insetsBottom(8)
        }

        if (isNewScope) {
            content.add(JBLabel("Scope directory"), gbc)
            gbc.gridy++
            content.add(scopeDirField, gbc)
            gbc.gridy++
        } else {
            content.add(JBLabel("Scope"), gbc)
            gbc.gridy++
            content.add(createWrappingText(scope!!.scopeDir.ifBlank { PROJECT_ROOT_DISPLAY }), gbc)
            gbc.gridy++
        }
        content.add(JBLabel("Base file"), gbc)
        gbc.gridy++
        content.add(baseFileField, gbc)
        gbc.gridy++
        content.add(JBLabel("Source language"), gbc)
        gbc.gridy++
        content.add(sourceLangField, gbc)

        panel.add(content, BorderLayout.CENTER)
        panel.add(
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(createWrappingText(dialogHintText()))
            },
            BorderLayout.SOUTH
        )

        return panel
    }

    override fun doValidateAll(): List<ValidationInfo> {
        val errors = mutableListOf<ValidationInfo>()
        if (isNewScope) {
            val scopeDirText = scopeDirField.text.trim()
            val resolvedScopeDir = resolveScopeDirectory(service, scopeDirText)
            when {
                scopeDirText.isBlank() -> errors += ValidationInfo("Choose a scope directory.", scopeDirField)
                resolvedScopeDir == null -> errors += ValidationInfo("Choose an existing directory.", scopeDirField)
                service.toStoredPath(resolvedScopeDir.path) in existingScopeDirs -> {
                    errors += ValidationInfo("A scope override for this directory already exists.", scopeDirField)
                }
            }
        }

        val baseFile = baseFileField.text.trim()
        val sourceLang = sourceLangField.text.trim()

        val hasBaseFile = baseFile.isNotBlank()
        val hasSourceLang = sourceLang.isNotBlank()
        if (hasBaseFile != hasSourceLang) {
            errors += ValidationInfo("Base file and source language should be set together.", if (hasBaseFile) sourceLangField else baseFileField)
        }

        if (hasBaseFile) {
            if (!baseFile.endsWith(".arb", ignoreCase = true)) {
                errors += ValidationInfo("Choose an ARB file.", baseFileField)
            }
            if (service.resolveStoredFile(baseFile) == null) {
                errors += ValidationInfo("Choose an existing file.", baseFileField)
            }
        }

        if (hasSourceLang && ArbLocaleHelper.normalizeLocale(sourceLang) == null) {
            errors += ValidationInfo("Enter a valid locale code.", sourceLangField)
        }

        return errors
    }

    val result: Result?
        get() {
            val scopeDir = if (isNewScope) {
                val resolvedScopeDir = resolveScopeDirectory(service, scopeDirField.text) ?: return null
                service.toStoredPath(resolvedScopeDir.path)
            } else {
                scope?.scopeDir.orEmpty()
            }
            val baseFile = baseFileField.text.trim()
            val sourceLang = sourceLangField.text.trim()
            val resolvedBaseFile = service.resolveStoredFile(baseFile)
            return Result(
                scopeDir = scopeDir,
                baseFile = resolvedBaseFile?.let { service.toStoredPath(it.path) }.orEmpty(),
                sourceLang = ArbLocaleHelper.normalizeLocale(sourceLang).orEmpty()
            )
        }

    private fun dialogHintText(): String {
        return if (isNewScope) {
            "Create the scope here first. Add target overrides later from the selected scope details. Leave base file and source language empty if you only want to add the scope now."
        } else {
            "Leave both fields empty to clear the saved base file and source language."
        }
    }

}

private fun createWrappingText(text: String): JTextArea {
    return JTextArea(text).apply {
        lineWrap = true
        wrapStyleWord = true
        isEditable = false
        isFocusable = false
        isOpaque = false
        border = JBUI.Borders.empty()
    }
}

class ArbTargetOverrideDialog(
    private val project: Project,
    private val service: ArbProjectOverridesService,
    initialValue: ArbProjectOverridesService.ArbTargetOverride?,
    private val otherTargetFiles: Set<String>
) : DialogWrapper(project) {
    private val targetFileField = createArbFileField(project, service, "Select Target ARB File")
    private val targetLangField = JTextField(initialValue?.targetLang.orEmpty(), 16)

    init {
        title = if (initialValue == null) "Add Target Override" else "Edit Target Override"
        targetFileField.text = initialValue?.targetFile.orEmpty()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.preferredSize = JBUI.size(520, 150)

        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.NORTHWEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = JBUI.insetsBottom(8)
        }

        panel.add(JBLabel("Target file"), gbc)
        gbc.gridy++
        panel.add(targetFileField, gbc)
        gbc.gridy++
        panel.add(JBLabel("Target language"), gbc)
        gbc.gridy++
        panel.add(targetLangField, gbc)

        return panel
    }

    override fun doValidateAll(): List<ValidationInfo> {
        val targetFile = targetFileField.text.trim()
        val resolvedFile = service.resolveStoredFile(targetFile)
        val normalizedTargetPath = resolvedFile?.let { service.toStoredPath(it.path) } ?: targetFile
        val targetLang = targetLangField.text.trim()
        val errors = mutableListOf<ValidationInfo>()

        if (targetFile.isBlank()) {
            errors += ValidationInfo("Choose a target file.", targetFileField)
        } else {
            if (!targetFile.endsWith(".arb", ignoreCase = true)) {
                errors += ValidationInfo("Choose an ARB file.", targetFileField)
            }
            if (resolvedFile == null) {
                errors += ValidationInfo("Choose an existing file.", targetFileField)
            }
            if (normalizedTargetPath in otherTargetFiles) {
                errors += ValidationInfo("An override for this file already exists.", targetFileField)
            }
        }

        if (ArbLocaleHelper.normalizeLocale(targetLang) == null) {
            errors += ValidationInfo("Enter a valid locale code.", targetLangField)
        }

        return errors
    }

    val result: ArbProjectOverridesService.ArbTargetOverride?
        get() {
            val resolvedFile = service.resolveStoredFile(targetFileField.text.trim()) ?: return null
            val targetLang = ArbLocaleHelper.normalizeLocale(targetLangField.text) ?: return null
            return ArbProjectOverridesService.ArbTargetOverride(
                targetFile = service.toStoredPath(resolvedFile.path),
                targetLang = targetLang
            )
        }

}

private fun createArbFileField(
    project: Project,
    service: ArbProjectOverridesService,
    dialogTitle: String
): TextFieldWithBrowseButton {
    return TextFieldWithBrowseButton().apply {
        addActionListener {
            val selectedFile = FileChooser.chooseFile(
                arbFileDescriptor(dialogTitle),
                project,
                currentSelection(service, text)
            )
            if (selectedFile != null) {
                text = service.toStoredPath(selectedFile.path)
            }
        }
    }
}

private fun createScopeDirectoryField(
    project: Project,
    service: ArbProjectOverridesService,
    dialogTitle: String
): TextFieldWithBrowseButton {
    return TextFieldWithBrowseButton().apply {
        addActionListener {
            val selectedDirectory = FileChooser.chooseFile(
                scopeDirectoryDescriptor(dialogTitle),
                project,
                currentScopeDirectorySelection(service, text)
            )
            if (selectedDirectory != null) {
                text = displayScopeDir(service.toStoredPath(selectedDirectory.path))
            }
        }
    }
}

private fun currentSelection(service: ArbProjectOverridesService, path: String): VirtualFile? {
    return service.resolveStoredFile(path.trim())
}

private fun currentScopeDirectorySelection(service: ArbProjectOverridesService, path: String): VirtualFile? {
    return resolveScopeDirectory(service, path)
}

private fun arbFileDescriptor(title: String): FileChooserDescriptor {
    return FileChooserDescriptor(true, false, false, false, false, false).apply {
        this.title = title
        withFileFilter { it.isDirectory || it.name.endsWith(".arb", ignoreCase = true) }
    }
}

private fun scopeDirectoryDescriptor(title: String): FileChooserDescriptor {
    return FileChooserDescriptor(false, true, false, false, false, false).apply {
        this.title = title
    }
}

private fun resolveScopeDirectory(service: ArbProjectOverridesService, path: String): VirtualFile? {
    val storedPath = normalizeScopeDirInput(path)
    return service.resolveStoredDirectory(storedPath)
}

private fun normalizeScopeDirInput(path: String): String {
    val trimmedPath = path.trim()
    return if (trimmedPath == PROJECT_ROOT_DISPLAY) "" else trimmedPath
}

private fun displayScopeDir(storedPath: String): String {
    return storedPath.ifBlank { PROJECT_ROOT_DISPLAY }
}

private const val PROJECT_ROOT_DISPLAY = "<project root>"
