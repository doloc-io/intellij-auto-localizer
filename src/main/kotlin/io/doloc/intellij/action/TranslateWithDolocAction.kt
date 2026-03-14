package io.doloc.intellij.action

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import io.doloc.intellij.api.DolocRequestBuilder
import io.doloc.intellij.arb.ArbPairResolver
import io.doloc.intellij.arb.ArbTranslationJob
import io.doloc.intellij.arb.ArbTranslationTargetsFinder
import io.doloc.intellij.arb.ArbTranslationWorkflow
import io.doloc.intellij.http.HttpClientProvider
import io.doloc.intellij.service.DolocSettingsService
import io.doloc.intellij.settings.DolocConfigurable
import io.doloc.intellij.settings.DolocSettingsState
import io.doloc.intellij.translation.SelectionValidationResult
import io.doloc.intellij.translation.TranslationKind
import io.doloc.intellij.util.utmUrl
import io.doloc.intellij.xliff.LightweightXliffScanner
import io.doloc.intellij.xliff.TargetLanguageAttribute
import io.doloc.intellij.xliff.TargetLanguageHelper
import io.doloc.intellij.xliff.XliffParseException
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class TranslateWithDolocAction @JvmOverloads constructor(
    private val xliffScanner: LightweightXliffScanner = LightweightXliffScanner(),
    private val showXliffParseError: (Project, String, String) -> Unit = { project, message, title ->
        Messages.showErrorDialog(project, message, title)
    }
) : AnAction("Translate with Auto Localizer") {
    private val log = logger<TranslateWithDolocAction>()
    private val notificationGroup =
        NotificationGroupManager.getInstance().getNotificationGroup("Doloc Translation")
    private val arbPairResolver = ArbPairResolver()
    private val arbTargetsFinder = ArbTranslationTargetsFinder(arbPairResolver)
    private val arbWorkflow = ArbTranslationWorkflow(
        pairResolver = arbPairResolver,
        targetsFinder = arbTargetsFinder,
        ensureApiToken = ::ensureApiToken,
        confirmOverwriteTargets = ::confirmOverwriteTargets,
        confirmFanOut = ::confirmArbFanOut,
        executeJobs = ::executeArbJobs
    )

    private data class RequestAuth(
        val token: String,
        val usesAnonymousToken: Boolean
    )

    override fun update(e: AnActionEvent) {
        val project = e.project
        val files = selectedFiles(e)
        val enabled = project != null && files.isNotEmpty() && files.any { isSupportedFile(it) }
        e.presentation.isEnabledAndVisible = enabled
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = selectedFiles(e)
        if (files.isEmpty()) return

        val validation = validateSelection(files)
        if (!validation.isValid) {
            Messages.showWarningDialog(project, validation.message.orEmpty(), validation.title ?: "Auto Localizer")
            return
        }

        when (validation.kind) {
            TranslationKind.XLIFF -> {
                if (files.size != 1) {
                    Messages.showInfoMessage(
                        project,
                        "Please select exactly one XLIFF file at a time.",
                        "Auto Localizer"
                    )
                    return
                }
                performTranslation(project, files.single())
            }

            TranslationKind.ARB -> {
                if (files.size == 1 && arbPairResolver.isScopeBase(project, files.single())) {
                    performArbBaseTranslation(project, files.single())
                } else {
                    performArbTranslation(project, files)
                }
            }

            else -> Unit
        }
    }

    fun performTranslation(project: Project, file: VirtualFile) {
        if (!ensureApiToken(project)) return

        FileDocumentManager.getInstance().saveAllDocuments()

        val settings = DolocSettingsState.getInstance()
        val scanResult = scanXliffOrShowParseError(project, file, settings) ?: return

        if (!confirmOverwriteTargets(project, listOf(file))) {
            return
        }

        val ensuredScanResult = ensureTargetLanguage(project, file, scanResult, settings) ?: return
        val translationScanResult = ensuredScanResult

        object : Task.Backgroundable(project, "Translating ${file.name}", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                try {
                    val untranslatedStates = if (translationScanResult.isXliff2) {
                        settings.xliff20UntranslatedStates
                    } else {
                        settings.xliff12UntranslatedStates
                    }

                    val newState = if (translationScanResult.isXliff2) {
                        settings.xliff20NewState
                    } else {
                        settings.xliff12NewState
                    }

                    val response = sendTranslationRequest {
                        DolocRequestBuilder.createTranslationRequest(
                            filePath = file,
                            token = it,
                            untranslatedStates = untranslatedStates,
                            newState = newState
                        )
                    }

                    if (response.statusCode() == 200) {
                        val responseText = String(response.body())
                        ApplicationManager.getApplication().invokeAndWait {
                            ApplicationManager.getApplication().runWriteAction {
                                VfsUtil.saveText(file, responseText)
                            }
                            showNotification(
                                project,
                                "Translation complete",
                                "Successfully translated ${file.name}",
                                NotificationType.INFORMATION
                            )
                        }
                    } else {
                        handleErrorResponse(project, response.statusCode(), response.body())
                    }
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Translation failed", e)
                    showNotification(
                        project,
                        "Translation failed",
                        e.message ?: "Unknown error",
                        NotificationType.ERROR
                    )
                }
            }
        }.queue()
    }

    fun performArbTranslation(project: Project, files: List<VirtualFile>) {
        arbWorkflow.performTranslation(project, files)
    }

    fun performArbBaseTranslation(
        project: Project,
        baseFile: VirtualFile,
        skipFanOutConfirmation: Boolean = false
    ) {
        arbWorkflow.performBaseTranslation(project, baseFile, skipFanOutConfirmation)
    }

    private fun executeArbJobs(project: Project, jobs: List<ArbTranslationJob>, taskTitle: String) {
        val settings = DolocSettingsState.getInstance()
        object : Task.Backgroundable(project, taskTitle, true) {
            override fun run(indicator: ProgressIndicator) {
                val failures = mutableListOf<String>()
                var successCount = 0

                jobs.forEachIndexed { index, job ->
                    indicator.text = "Translating ${job.targetFile.name} (${index + 1}/${jobs.size})"
                    indicator.fraction = (index.toDouble() / jobs.size.toDouble()).coerceIn(0.0, 1.0)

                    try {
                        val response = sendTranslationRequest {
                            DolocRequestBuilder.createArbTranslationRequest(
                                sourceFile = job.baseFile,
                                targetFile = job.targetFile,
                                token = it,
                                untranslatedStates = settings.arbUntranslatedStates,
                                sourceLang = job.sourceLang,
                                targetLang = job.targetLang
                            )
                        }

                        if (response.statusCode() == 200) {
                            val responseText = String(response.body())
                            ApplicationManager.getApplication().invokeAndWait {
                                ApplicationManager.getApplication().runWriteAction {
                                    VfsUtil.saveText(job.targetFile, responseText)
                                }
                            }
                            successCount++
                        } else {
                            if (response.statusCode() == 402) {
                                handleErrorResponse(project, response.statusCode(), response.body())
                                return
                            }
                            failures += formatFailure(job.targetFile.name, response.statusCode(), response.body())
                        }
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("ARB translation failed for ${job.targetFile.path}", e)
                        failures += "${job.targetFile.name}: ${e.message ?: "Unknown error"}"
                    }
                }

                ApplicationManager.getApplication().invokeLater {
                    when {
                        failures.isEmpty() -> showNotification(
                            project,
                            "Translation complete",
                            "Successfully translated ${successCount} ARB file(s).",
                            NotificationType.INFORMATION
                        )

                        successCount == 0 -> showNotification(
                            project,
                            "Translation failed",
                            failures.joinToString("\n"),
                            NotificationType.ERROR
                        )

                        else -> showNotification(
                            project,
                            "Translation finished with issues",
                            buildString {
                                append("Translated ")
                                append(successCount)
                                append(" ARB file(s).\n")
                                append(failures.joinToString("\n"))
                            },
                            NotificationType.WARNING
                        )
                    }
                }
            }
        }.queue()
    }

    private fun confirmArbFanOut(project: Project, baseFile: VirtualFile, targets: List<VirtualFile>): Boolean {
        val listedTargets = targets.take(8).joinToString("\n") { "- ${it.name}" }
        val extraCount = (targets.size - 8).coerceAtLeast(0)
        val message = buildString {
            append("Translate ${baseFile.name} into the following target files?\n\n")
            append(listedTargets)
            if (extraCount > 0) {
                append("\n- +")
                append(extraCount)
                append(" more")
            }
        }
        return Messages.showYesNoDialog(
            project,
            message,
            "Auto Localizer",
            "Translate",
            "Cancel",
            Messages.getQuestionIcon()
        ) == Messages.YES
    }

    private fun confirmOverwriteTargets(project: Project, targets: List<VirtualFile>): Boolean {
        val unversionedTargets = targets.filter { ChangeListManager.getInstance(project).isUnversioned(it) }
        if (unversionedTargets.isEmpty()) {
            return true
        }

        val names = unversionedTargets.take(6).joinToString("\n") { "- ${it.name}" }
        val extraCount = (unversionedTargets.size - 6).coerceAtLeast(0)
        val message = buildString {
            append("The following target files are not under version control. Overwrite anyway?\n\n")
            append(names)
            if (extraCount > 0) {
                append("\n- +")
                append(extraCount)
                append(" more")
            }
        }

        return Messages.showYesNoDialog(
            project,
            message,
            "Auto Localizer",
            "Overwrite",
            "Cancel",
            Messages.getWarningIcon()
        ) == Messages.YES
    }

    private fun ensureApiToken(project: Project): Boolean {
        if (DolocSettingsService.getInstance().getApiToken() != null) {
            return true
        }

        showNotification(
            project,
            "No API Token configured!",
            "Please fetch a (free) API token from doloc.io/account and enter it in settings.",
            NotificationType.ERROR,
            object : AnAction("Visit doloc.io/account") {
                override fun actionPerformed(e: AnActionEvent) {
                    BrowserUtil.browse(utmUrl("https://doloc.io/account", "action_no_token"))
                }
            },
            object : AnAction("Open Settings") {
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(
                        project,
                        DolocConfigurable::class.java
                    )
                }
            }
        )
        return false
    }

    private fun validateSelection(files: List<VirtualFile>): SelectionValidationResult {
        val kinds = files.map {
            when {
                isArbFile(it) -> TranslationKind.ARB
                isXliffFile(it) -> TranslationKind.XLIFF
                else -> TranslationKind.UNSUPPORTED
            }
        }.toSet()

        return when {
            kinds.contains(TranslationKind.UNSUPPORTED) -> SelectionValidationResult(
                kind = null,
                isValid = false,
                title = "Auto Localizer",
                message = "The current selection mixes supported files with unsupported files. Please select only ARB files or only one XLIFF file."
            )

            kinds.size > 1 -> SelectionValidationResult(
                kind = null,
                isValid = false,
                title = "Auto Localizer",
                message = "The current selection mixes ARB and XLIFF files. Please translate one format at a time."
            )

            kinds.singleOrNull() == TranslationKind.ARB -> SelectionValidationResult(TranslationKind.ARB, true)
            kinds.singleOrNull() == TranslationKind.XLIFF -> SelectionValidationResult(TranslationKind.XLIFF, true)
            else -> SelectionValidationResult(null, false, "Auto Localizer", "No supported translation files were selected.")
        }
    }

    private fun ensureTargetLanguage(
        project: Project,
        file: VirtualFile,
        scanResult: LightweightXliffScanner.ScanResult,
        settings: DolocSettingsState
    ): LightweightXliffScanner.ScanResult? {
        if (!scanResult.targetLanguageValue.isNullOrBlank()) {
            return scanResult
        }

        val attribute = scanResult.targetLanguageAttribute
        val baseMessage = buildString {
            append("File \"")
            append(file.name)
            append("\" is missing the required \"")
            append(attribute.attributeName)
            append("\" attribute on <")
            append(attribute.elementName)
            append(">.\nPlease set the target language before translating.")
        }

        val filenameLanguage = TargetLanguageHelper.guessLanguageFromFilename(file.name)
        if (filenameLanguage.isNullOrBlank()) {
            Messages.showWarningDialog(project, baseMessage, "Auto Localizer")
            return null
        }

        val dialogMessage = buildString {
            append(baseMessage)
            append("\n\nDetected \"")
            append(filenameLanguage)
            append("\" from the filename. Add it automatically?")
        }
        val applyButtonLabel = "Add ${attribute.attributeName}=\"$filenameLanguage\""
        val selected = Messages.showDialog(
            project,
            dialogMessage,
            "Auto Localizer",
            arrayOf(applyButtonLabel, "Cancel"),
            0,
            Messages.getWarningIcon()
        )
        if (selected != 0) {
            return null
        }

        if (!applyTargetLanguageQuickFix(file, attribute, filenameLanguage)) {
            Messages.showErrorDialog(
                project,
                "Unable to update the file automatically. Please add ${attribute.attributeName}=\"$filenameLanguage\" manually and retry.",
                "Auto Localizer"
            )
            return null
        }

        val rescanned = scanXliffOrShowParseError(project, file, settings) ?: return null
        if (rescanned.targetLanguageValue.isNullOrBlank()) {
            Messages.showErrorDialog(
                project,
                "The target language attribute is still missing after applying the quick fix. Please update the file manually and retry.",
                "Auto Localizer"
            )
            return null
        }

        return rescanned
    }

    private fun scanXliffOrShowParseError(
        project: Project,
        file: VirtualFile,
        settings: DolocSettingsState
    ): LightweightXliffScanner.ScanResult? {
        return try {
            xliffScanner.scan(
                file,
                settings.xliff12UntranslatedStates,
                settings.xliff20UntranslatedStates
            )
        } catch (e: XliffParseException) {
            handleXliffParseFailure(project, e)
            null
        }
    }

    private fun handleXliffParseFailure(project: Project, exception: XliffParseException) {
        showXliffParseError(project, exception.toUserMessage(), "XLIFF Translation")
    }

    private fun applyTargetLanguageQuickFix(
        file: VirtualFile,
        attribute: TargetLanguageAttribute,
        language: String
    ): Boolean {
        return try {
            val currentText = VfsUtil.loadText(file)
            val updatedText = TargetLanguageHelper.addTargetLanguageAttribute(currentText, attribute, language)
                ?: return false
            ApplicationManager.getApplication().runWriteAction {
                VfsUtil.saveText(file, updatedText)
            }
            true
        } catch (e: Exception) {
            log.warn("Failed to apply target language quick fix", e)
            false
        }
    }

    private fun handleErrorResponse(project: Project, statusCode: Int, body: ByteArray) {
        if (statusCode == 402) {
            val isAnonymousToken = DolocSettingsState.getInstance().useAnonymousToken
            if (isAnonymousToken) {
                showNotification(
                    project,
                    "Translation failed: Quota exceeded!",
                    "Your monthly free quota of 100 source texts is used. Please register an account on doloc.io with a paid plan for increased quota.",
                    NotificationType.ERROR,
                    object : AnAction("Register on doloc.io") {
                        override fun actionPerformed(e: AnActionEvent) {
                            BrowserUtil.browse(utmUrl("https://doloc.io/account", "action_quota_exceeded"))
                        }
                    },
                    object : AnAction("Configure API Token in Settings") {
                        override fun actionPerformed(e: AnActionEvent) {
                            ShowSettingsUtil.getInstance().showSettingsDialog(
                                project,
                                DolocConfigurable::class.java
                            )
                        }
                    }
                )
            } else {
                showNotification(
                    project,
                    "Translation failed: Quota exceeded!",
                    "Your monthly quota is used. Consider upgrading your account.",
                    NotificationType.ERROR,
                    object : AnAction("Visit doloc.io/account") {
                        override fun actionPerformed(e: AnActionEvent) {
                            BrowserUtil.browse(utmUrl("https://doloc.io/account", "action_quota_exceeded"))
                        }
                    }
                )
            }
            return
        }

        val responseBody = try {
            String(body).trim()
        } catch (_: Exception) {
            ""
        }

        val message = buildString {
            append("Translation failed with status: $statusCode")
            if (responseBody.isNotEmpty()) {
                append('\n')
                append(responseBody)
            }
        }
        throw IllegalStateException(message)
    }

    private fun sendTranslationRequest(buildRequest: (String) -> HttpRequest): HttpResponse<ByteArray> {
        val auth = currentRequestAuth()
        val response = HttpClientProvider.client.send(
            buildRequest(auth.token),
            HttpResponse.BodyHandlers.ofByteArray()
        )

        if (!shouldRetryWithFreshAnonymousToken(auth, response)) {
            return response
        }

        val refreshedToken = DolocSettingsService.getInstance().refreshAnonymousToken(auth.token)
        if (refreshedToken.isNullOrBlank()) {
            return response
        }

        log.info("Retrying translation request with refreshed anonymous token")
        return HttpClientProvider.client.send(
            buildRequest(refreshedToken),
            HttpResponse.BodyHandlers.ofByteArray()
        )
    }

    private fun currentRequestAuth(): RequestAuth {
        val usesAnonymousToken = DolocSettingsState.getInstance().useAnonymousToken
        val token = DolocSettingsService.getInstance().getApiToken()
            ?: throw IllegalStateException("No API token available")
        return RequestAuth(token, usesAnonymousToken)
    }

    private fun shouldRetryWithFreshAnonymousToken(
        auth: RequestAuth,
        response: HttpResponse<ByteArray>
    ): Boolean {
        if (!auth.usesAnonymousToken || response.statusCode() != 401) {
            return false
        }

        return getResponseBodyText(response.body()).contains("invalid api token", ignoreCase = true)
    }

    private fun getResponseBodyText(body: ByteArray): String {
        return try {
            String(body).trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun formatFailure(targetName: String, statusCode: Int, body: ByteArray): String {
        val responseBody = getResponseBodyText(body)
        return if (responseBody.isBlank()) {
            "$targetName: status $statusCode"
        } else {
            "$targetName: status $statusCode - $responseBody"
        }
    }

    private fun selectedFiles(e: AnActionEvent): List<VirtualFile> {
        val selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
            ?.toList()
            .orEmpty()
            .distinctBy { it.path }
        if (selectedFiles.isNotEmpty()) {
            return selectedFiles
        }

        val singleFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return emptyList()
        return listOf(singleFile)
    }

    private fun isSupportedFile(file: VirtualFile): Boolean = isArbFile(file) || isXliffFile(file)

    private fun isArbFile(file: VirtualFile): Boolean = file.extension.equals("arb", ignoreCase = true)

    private fun isXliffFile(file: VirtualFile): Boolean {
        val extension = file.extension ?: return false
        return extension.equals("xlf", ignoreCase = true) || extension.equals("xliff", ignoreCase = true)
    }

    private fun showNotification(
        project: Project,
        title: String,
        content: String,
        type: NotificationType,
        vararg actions: AnAction
    ) {
        val notification = notificationGroup.createNotification(title, content, type)
        if (actions.isNotEmpty()) notification.addActions(actions.asList() as Collection<AnAction>)
        notification.notify(project)
    }
}
