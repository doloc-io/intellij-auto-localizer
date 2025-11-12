package io.doloc.intellij.listener

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CustomizedDataContext
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import io.doloc.intellij.action.TranslateWithDolocAction
import io.doloc.intellij.filetype.XliffFileType
import io.doloc.intellij.settings.DolocSettingsState
import io.doloc.intellij.util.logger
import io.doloc.intellij.xliff.LightweightXliffScanner
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Service
class DolocFileListener : BulkFileListener {
    private val log = logger<DolocFileListener>()
    private val recentNotifications = ConcurrentHashMap<String, Instant>()
    private val debounceTimeMs = 10_000L // 10 seconds debounce
    private val scanner = LightweightXliffScanner()
    private val notificationGroup =
        NotificationGroupManager.getInstance().getNotificationGroup("Doloc Translation")

    override fun after(events: List<VFileEvent>) {
        if (!DolocSettingsState.getInstance().showReminderToast) {
            return
        }

        val xliffChanges = events
            .filter { it.file != null && it.file!!.isValid }
            .filter { it.file!!.fileType is XliffFileType }
            .mapNotNull { it.file }

        if (xliffChanges.isEmpty()) return

        // Use non-UI thread to check files
        ApplicationManager.getApplication().executeOnPooledThread {
            for (file in xliffChanges) {
                checkFileAndNotify(file)
            }
        }
    }

    private fun checkFileAndNotify(file: VirtualFile) {
        try {
            val ioFile = File(file.path)
            if (!ioFile.exists()) return

            // Debounce: check if we've shown a notification for this file recently
            val path = file.path
            val lastNotification = recentNotifications[path]
            if (lastNotification != null &&
                Instant.now().toEpochMilli() - lastNotification.toEpochMilli() < debounceTimeMs
            ) {
                return
            }

            val scanResult = scanner.scan(
                file,
                DolocSettingsState.getInstance().xliff12UntranslatedStates,
                DolocSettingsState.getInstance().xliff20UntranslatedStates
            )
            if (scanResult.hasUntranslatedUnits) {
                // Record notification time to prevent duplicates
                recentNotifications[file.path] = Instant.now()

                // Find a valid project for this file
                val project = ProjectManager.getInstance().openProjects.firstOrNull { project ->
                    !project.isDisposed && project.isInitialized
                } ?: return

                ApplicationManager.getApplication().invokeLater {
                    showNotification(project, file)
                }
            }
        } catch (e: Exception) {
            log.warn("Error checking XLIFF file for untranslated units: ${e.message}")
        }
    }

    private fun showNotification(project: Project, file: VirtualFile) {
        val notification = notificationGroup.createNotification(
            "Untranslated strings found",
            "File '${file.name}' contains untranslated strings",
            NotificationType.INFORMATION
        )

        notification.addAction(object : AnAction("Translate with Auto Localizer") {
            override fun actionPerformed(e: AnActionEvent) {
                val action = TranslateWithDolocAction()
                action.performTranslation(project, file)
                notification.expire()
            }
        })

        notification.addAction(object : AnAction("Never show this again") {
            override fun actionPerformed(e: AnActionEvent) {
                DolocSettingsState.getInstance().showReminderToast = false
                notification.expire()
            }
        })

        notification.notify(project)
    }
}
