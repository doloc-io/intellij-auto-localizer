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
import io.doloc.intellij.settings.DolocConfigurable
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
                        BrowserUtil.browse("https://doloc.io/account")
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

        // Start the translation as a background task
        object : Task.Backgroundable(project, "Translating ${file.name}", true) {
            @Suppress("DialogTitleCapitalization")
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                try {
                    val settings = DolocSettingsState.getInstance()
                    val scanResult = LightweightXliffScanner().scan(
                        file,
                        settings.xliff12UntranslatedStates,
                        settings.xliff20UntranslatedStates
                    )

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
                        showNotification(
                            project,
                            "Translation failed: Quota exceeded!",
                            "Your monthly quota is used. Consider upgrading your account.",
                            NotificationType.ERROR,
                            object : AnAction("Visit doloc.io/account") {
                                override fun actionPerformed(e: AnActionEvent) {
                                    BrowserUtil.browse("https://doloc.io/account")
                                }
                            }
                        )
                    } else {
                        throw IllegalStateException("Translation failed with status: ${response.statusCode()}")
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
