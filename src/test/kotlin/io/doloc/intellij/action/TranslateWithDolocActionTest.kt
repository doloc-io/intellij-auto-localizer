package io.doloc.intellij.action

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.MapDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.doloc.intellij.api.DolocRequestBuilder
import io.doloc.intellij.service.DolocSettingsService
import io.doloc.intellij.settings.DolocSettingsState
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TranslateWithDolocActionTest : BasePlatformTestCase() {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var originalBaseUrl: String
    private lateinit var tempDir: Path
    private lateinit var xliffFile: VirtualFile

    override fun setUp() {
        super.setUp()

        // Save the original base URL
        originalBaseUrl = DolocRequestBuilder.getBaseUrl()

        // Start mock server
        mockWebServer = MockWebServer()
        mockWebServer.start()

        // Set base URL to mock server using the new API method
        DolocRequestBuilder.setBaseUrl(mockWebServer.url("/").toString().removeSuffix("/"))

        // Set a test token in the settings
        val settingsService = DolocSettingsService.getInstance()
        settingsService.setApiToken("test-token")
        DolocSettingsState.getInstance().useAnonymousToken = false

        // Create a temp directory and XLIFF file for testing
        tempDir = Files.createTempDirectory("doloc-test")
        val untranslatedContent = File(javaClass.classLoader.getResource("xliff/untranslated.xlf")!!.file)
            .readText()

        // Create physical file
        val physicalFile = tempDir.resolve("test.xlf").toFile()
        physicalFile.writeText(untranslatedContent)

        // Create virtual file
        xliffFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(physicalFile)!!

        // Refresh to make sure the file is visible in the virtual file system
        VfsUtil.markDirtyAndRefresh(false, false, false, xliffFile)
    }

    override fun tearDown() {
        try {
            // Restore original base URL using the API method
            DolocRequestBuilder.setBaseUrl(originalBaseUrl)

            // Shutdown mock server
            mockWebServer.shutdown()

            // Clean up temp files
            tempDir.toFile().deleteRecursively()
            val settingsService = DolocSettingsService.getInstance()
            settingsService.clearApiToken()
            settingsService.clearAnonymousToken()
            DolocSettingsState.getInstance().useAnonymousToken = true
        } finally {
            super.tearDown()
        }
    }

    fun testTranslateAction() {
        // Mock the response from the server
        val translatedContent = File(javaClass.classLoader.getResource("xliff/fully_translated.xlf")!!.file)
            .readText()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(translatedContent)
        )

        // Create action
        val action = TranslateWithDolocAction()

        // Create a data context with our test file
        val dataContext = MapDataContext()
        dataContext.put(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(xliffFile))
        dataContext.put(CommonDataKeys.PROJECT, project)

        // Create action event
        val event = AnActionEvent.createFromDataContext("test", null, dataContext)

        // Execute the action (it runs in background, so we need to wait)
        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().runWriteAction {
            DolocRequestBuilder.setBaseUrl(mockWebServer.url("/").toString().removeSuffix("/"))
            action.actionPerformed(event)
            latch.countDown()
        }

        // Wait for action to complete
        assertTrue("Action timed out", latch.await(5, TimeUnit.SECONDS))

        ApplicationManager.getApplication().invokeAndWait {
            xliffFile.refresh(false, false)
        }

        // Verify the request was made correctly
        val recordedRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("No request was recorded", recordedRequest)
        assertEquals("POST", recordedRequest?.method)
        assertTrue(recordedRequest?.path?.startsWith("/") ?: false)
        assertEquals("Bearer test-token", recordedRequest?.getHeader("Authorization"))

        // Verify file was updated with translated content
        val actualContent = VfsUtil.loadText(xliffFile)
        assertTrue(
            "File should contain translation state='translated'",
            actualContent.contains("state=\"translated\"")
        )
    }

    fun testTranslateActionWithAnonymousToken() {
        // Store anonymous token and enable anonymous mode
        val settingsService = DolocSettingsService.getInstance()
        settingsService.clearApiToken()
        settingsService.setAnonymousToken("anon-token")
        DolocSettingsState.getInstance().useAnonymousToken = true

        val translatedContent = File(
            javaClass.classLoader.getResource("xliff/fully_translated.xlf")!!.file
        ).readText()
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(translatedContent)
        )

        val action = TranslateWithDolocAction()

        val dataContext = MapDataContext()
        dataContext.put(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(xliffFile))
        dataContext.put(CommonDataKeys.PROJECT, project)

        val event = AnActionEvent.createFromDataContext("test", null, dataContext)

        val latch = CountDownLatch(1)
        ApplicationManager.getApplication().runWriteAction {
            DolocRequestBuilder.setBaseUrl(mockWebServer.url("/").toString().removeSuffix("/"))
            action.actionPerformed(event)
            latch.countDown()
        }

        assertTrue("Action timed out", latch.await(5, TimeUnit.SECONDS))

        ApplicationManager.getApplication().invokeAndWait {
            xliffFile.refresh(false, false)
        }

        val recordedRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("No request was recorded", recordedRequest)
        assertEquals("POST", recordedRequest?.method)
        assertEquals("Bearer anon-token", recordedRequest?.getHeader("Authorization"))

        val actualContent = VfsUtil.loadText(xliffFile)
        assertTrue(actualContent.contains("state=\"translated\""))
    }
}

