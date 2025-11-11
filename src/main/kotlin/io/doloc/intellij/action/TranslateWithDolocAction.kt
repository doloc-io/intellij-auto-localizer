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
import io.doloc.intellij.http.HttpClientProvider
import io.doloc.intellij.service.DolocSettingsService
import io.doloc.intellij.settings.DolocSettingsState
import io.doloc.intellij.xliff.LightweightXliffScanner
import io.doloc.intellij.xliff.TargetLanguageAttributeHelper
import io.doloc.intellij.settings.DolocConfigurable
import io.doloc.intellij.util.utmUrl

import java.net.http.HttpResponse

class TranslateWithDolocAction : AnAction("Translate with Auto Localizer") {
    private val log = logger<TranslateWithDolocAction>()
    private val notificationGroup =
        NotificationGroupManager.getInstance().getNotificationGroup("Doloc Translation")

    override fun update(e: AnActionEvent) {
        val project = e.project
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)

        // Enable only if we have a project and exactly one XLIFF file
        val enabled = project != null && files != null &&
                files.size == 1 &&
                files[0].extension?.lowercase() in listOf("xlf", "xliff")

        e.presentation.isEnabledAndVisible = enabled
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.firstOrNull() ?: return

        performTranslation(project, file)
    }

    fun performTranslation(
        project: Project,
        file: VirtualFile
    ) {
        if (DolocSettingsService.getInstance().getApiToken() == null) {
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
            return
        }

        // Save all documents to ensure we're working with the latest content
        FileDocumentManager.getInstance().saveAllDocuments()

        // Check if file is under VCS
        val changeListManager = ChangeListManager.getInstance(project)
        val isUnderVcs = !changeListManager.isUnversioned(file)

        // If not under VCS, show warning dialog
        if (!isUnderVcs) {
            val result = Messages.showYesNoDialog(
                project,
                "File \"${file.name}\" is not under version control. Overwrite anyway?",
                "doloc Translation",
                "Overwrite",
                "Cancel",
                Messages.getWarningIcon()
            )
            if (result != Messages.YES) {
                return
            }
        }

        val settings = DolocSettingsState.getInstance()
        val scanResult = try {
            LightweightXliffScanner().scan(
                file,
                settings.xliff12UntranslatedStates,
                settings.xliff20UntranslatedStates
            )
        } catch (exception: Exception) {
            log.warn("Failed to scan XLIFF file", exception)
            showNotification(
                project,
                "Translation failed",
                exception.message ?: "Could not inspect ${file.name}",
                NotificationType.ERROR
            )
            return
        }

        if (!scanResult.hasTargetLanguageAttribute) {
            handleMissingTargetLanguage(project, file, scanResult.isXliff2)
            return
        }

        val untranslatedStates = if (scanResult.isXliff2) {
            settings.xliff20UntranslatedStates
        } else {
            settings.xliff12UntranslatedStates
        }

        val newState = if (scanResult.isXliff2) {
            settings.xliff20NewState
        } else {
            settings.xliff12NewState
        }

        // Start the translation as a background task
        object : Task.Backgroundable(project, "Translating ${file.name}", true) {

            @Suppress("DialogTitleCapitalization")
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                try {
                    val request = DolocRequestBuilder.createTranslationRequest(
                        file,
                        untranslatedStates = untranslatedStates,
                        newState = newState
                    )


                    // Send request
                    val response = HttpClientProvider.client.send(
                        request,
                        HttpResponse.BodyHandlers.ofByteArray()
                    )

                    if (response.statusCode() == 200) {
                        val responseText = String(response.body())

                        // First get on the EDT, then run the write action
                        ApplicationManager.getApplication()
                            .invokeAndWait {
                                ApplicationManager.getApplication()
                                    .runWriteAction {
                                        VfsUtil.saveText(file, responseText)
                                    }

                                // Show notification after the file is saved
                                showNotification(
                                    project,
                                    "Translation complete",
                                    "Successfully translated ${file.name}",
                                    NotificationType.INFORMATION
                                )
                            }
                    } else if (response.statusCode() == 402) {
                        val isAnonymousToken = settings.useAnonymousToken

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
                    } else {
                        val responseBody = try {
                            String(response.body()).trim()
                        } catch (ignored: Exception) {
                            ""
                        }

                        val message = buildString {
                            append("Translation failed with status: ${response.statusCode()}")
                            if (responseBody.isNotEmpty()) {
                                append('\n')
                                append(responseBody)
                            }
                        }

                        throw IllegalStateException(message)
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

    private fun handleMissingTargetLanguage(
        project: Project,
        file: VirtualFile,
        isXliff2: Boolean
    ) {
        val attributeName = if (isXliff2) "trgLang" else "target-language"
        val elementName = if (isXliff2) "&lt;xliff&gt;" else "&lt;file&gt;"
        val message = buildString {
            append("The $elementName element in ${file.name} is missing the \"$attributeName\" attribute required for translation.")
            append('\n')
            append("\"Add Attribute\" will attempt to to guess the target langauge from the file name and insert the corret attribute. Afterwards, you can review and edit the inserted value if needed.")
        }
        val result = Messages.showYesNoDialog(
            project,
            message,
            "doloc Translation: Target Language Missing",
            "Add Attribute",
            "Cancel",
            Messages.getWarningIcon()
        )
        if (result == Messages.YES) {
            TargetLanguageAttributeHelper.addMissingTargetLanguageAttribute(project, file, isXliff2)
        }
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

