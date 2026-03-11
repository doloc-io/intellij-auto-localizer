package io.doloc.intellij.listener

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import io.doloc.intellij.action.TranslateWithDolocAction
import io.doloc.intellij.arb.ArbReminderInspector
import io.doloc.intellij.arb.ArbParseException
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
    private val debounceTimeMs = 10_000L
    private val xliffScanner = LightweightXliffScanner()
    private val arbReminderInspector = ArbReminderInspector()
    private val notificationGroup =
        NotificationGroupManager.getInstance().getNotificationGroup("Doloc Translation")

    override fun after(events: List<VFileEvent>) {
        if (!DolocSettingsState.getInstance().showReminderToast) {
            return
        }

        val changedFiles = events
            .mapNotNull { it.file }
            .filter { it.isValid }
            .filter { isSupportedReminderFile(it) }

        if (changedFiles.isEmpty()) return

        ApplicationManager.getApplication().executeOnPooledThread {
            changedFiles.forEach { file ->
                checkFileAndNotify(file)
            }
        }
    }

    private fun checkFileAndNotify(file: VirtualFile) {
        try {
            if (!File(file.path).exists()) return
            if (isDebounced(file)) return

            val project = resolveProject(file) ?: return
            if (isArb(file)) {
                val reminder = arbReminderInspector.inspect(
                    project,
                    file,
                    DolocSettingsState.getInstance().arbUntranslatedStates
                ) ?: return
                recentNotifications[file.path] = Instant.now()
                ApplicationManager.getApplication().invokeLater {
                    showArbNotification(project, file, reminder)
                }
                return
            }

            val scanResult = xliffScanner.scan(
                file,
                DolocSettingsState.getInstance().xliff12UntranslatedStates,
                DolocSettingsState.getInstance().xliff20UntranslatedStates
            )
            if (!scanResult.hasUntranslatedUnits) return

            recentNotifications[file.path] = Instant.now()
            ApplicationManager.getApplication().invokeLater {
                showXliffNotification(project, file)
            }
        } catch (_: ArbParseException) {
            return
        } catch (e: Exception) {
            log.warn("Error checking file for untranslated units: ${e.message}")
        }
    }

    private fun showXliffNotification(project: Project, file: VirtualFile) {
        val notification = notificationGroup.createNotification(
            "Untranslated strings found",
            "File '${file.name}' contains untranslated strings.",
            NotificationType.INFORMATION
        )

        notification.addAction(object : AnAction("Translate with Auto Localizer") {
            override fun actionPerformed(e: AnActionEvent) {
                TranslateWithDolocAction().performTranslation(project, file)
                notification.expire()
            }
        })

        notification.addAction(disableReminderAction(notification))
        notification.notify(project)
    }

    private fun showArbNotification(
        project: Project,
        triggeringFile: VirtualFile,
        reminder: ArbReminderInspector.Reminder
    ) {
        val notification = when (reminder.type) {
            ArbReminderInspector.ReminderType.TARGET -> notificationGroup.createNotification(
                "ARB file needs translation",
                "${triggeringFile.name} can be translated from ${reminder.baseFile.name}.",
                NotificationType.INFORMATION
            )

            ArbReminderInspector.ReminderType.BASE -> notificationGroup.createNotification(
                "Base ARB changed",
                buildBaseHintContent(reminder.targetFiles),
                NotificationType.INFORMATION
            )
        }

        when (reminder.type) {
            ArbReminderInspector.ReminderType.TARGET -> {
                notification.addAction(object : AnAction("Translate this file") {
                    override fun actionPerformed(e: AnActionEvent) {
                        TranslateWithDolocAction().performArbTranslation(project, listOf(triggeringFile))
                        notification.expire()
                    }
                })
            }

            ArbReminderInspector.ReminderType.BASE -> {
                notification.addAction(object : AnAction("Translate to all target files") {
                    override fun actionPerformed(e: AnActionEvent) {
                        TranslateWithDolocAction().performArbBaseTranslation(project, triggeringFile)
                        notification.expire()
                    }
                })
            }
        }

        notification.addAction(disableReminderAction(notification))
        notification.notify(project)
    }

    private fun buildBaseHintContent(targetFiles: List<VirtualFile>): String {
        val listed = targetFiles.take(5).joinToString(", ") { it.name }
        val extraCount = (targetFiles.size - 5).coerceAtLeast(0)
        return if (extraCount == 0) {
            "Translate to target files: $listed"
        } else {
            "Translate to target files: $listed, +$extraCount more"
        }
    }

    private fun disableReminderAction(notification: com.intellij.notification.Notification): AnAction {
        return object : AnAction("Never show this again") {
            override fun actionPerformed(e: AnActionEvent) {
                DolocSettingsState.getInstance().showReminderToast = false
                notification.expire()
            }
        }
    }

    private fun resolveProject(file: VirtualFile): Project? {
        return ProjectLocator.getInstance().getProjectsForFile(file)
            .firstOrNull { it != null && !it.isDisposed && it.isInitialized }
    }

    private fun isSupportedReminderFile(file: VirtualFile): Boolean {
        return file.fileType is XliffFileType || isArb(file)
    }

    private fun isArb(file: VirtualFile): Boolean = file.extension.equals("arb", ignoreCase = true)

    private fun isDebounced(file: VirtualFile): Boolean {
        val lastNotification = recentNotifications[file.path] ?: return false
        return Instant.now().toEpochMilli() - lastNotification.toEpochMilli() < debounceTimeMs
    }
}
