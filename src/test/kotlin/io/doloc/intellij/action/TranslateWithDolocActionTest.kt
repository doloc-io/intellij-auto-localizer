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
import java.util.concurrent.TimeUnit

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
        val translatedContent = File(javaClass.classLoader.getResource("xliff/fully_translated.xlf")!!.file)
            .readText()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(translatedContent)
        )

        executeAction()

        val recordedRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("No request was recorded", recordedRequest)
        assertEquals("POST", recordedRequest?.method)
        assertTrue(recordedRequest?.path?.startsWith("/") ?: false)
        assertEquals("Bearer test-token", recordedRequest?.getHeader("Authorization"))

        waitForTranslationResult()
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

        executeAction()

        val recordedRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("No request was recorded", recordedRequest)
        assertEquals("POST", recordedRequest?.method)
        assertEquals("Bearer anon-token", recordedRequest?.getHeader("Authorization"))

        waitForTranslationResult()
    }

    fun testTranslateActionRetriesOnceWithRefreshedAnonymousToken() {
        val settingsService = DolocSettingsService.getInstance()
        settingsService.clearApiToken()
        settingsService.setAnonymousToken("anon-token")
        DolocSettingsState.getInstance().useAnonymousToken = true

        val translatedContent = File(
            javaClass.classLoader.getResource("xliff/fully_translated.xlf")!!.file
        ).readText()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("Invalid API token for anonymous user")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"token":"refreshed-anon-token","user_id":"1234-1234-1234-1234"}""")
                .addHeader("Content-Type", "application/json")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(translatedContent)
        )

        executeAction()

        val firstRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(firstRequest)
        assertEquals("POST", firstRequest?.method)
        assertEquals("Bearer anon-token", firstRequest?.getHeader("Authorization"))

        val tokenRefreshRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(tokenRefreshRequest)
        assertEquals("PUT", tokenRefreshRequest?.method)
        assertEquals("/token/anonymous", tokenRefreshRequest?.path)

        val retriedRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(retriedRequest)
        assertEquals("POST", retriedRequest?.method)
        assertEquals("Bearer refreshed-anon-token", retriedRequest?.getHeader("Authorization"))
        assertEquals("refreshed-anon-token", settingsService.getStoredAnonymousToken())

        waitForTranslationResult()
    }

    fun testTranslateActionDoesNotRetryManualToken401() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("Invalid API token for configured account")
        )

        executeAction()

        val recordedRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recordedRequest)
        assertEquals("POST", recordedRequest?.method)
        assertEquals("Bearer test-token", recordedRequest?.getHeader("Authorization"))
        assertNull(mockWebServer.takeRequest(1, TimeUnit.SECONDS))
    }

    fun testTranslateActionAbortsWhenXliffIsMalformed() {
        val malformedFile = createVirtualFile(
            "malformed.xlf",
            """<?xml version="1.0" encoding="UTF-8"?>
<xliff version="1.2">
  <file source-language="en" target-language="fr">
    <body>
      <trans-unit id="1">
        <source>Hello</source>
        <target>Hello</source>
      </trans-unit>
    </body>
  </file>
</xliff>"""
        )
        val parseErrors = mutableListOf<String>()
        val action = TranslateWithDolocAction(
            showXliffParseError = { _, message, _ -> parseErrors += message }
        )

        executeAction(action, malformedFile)

        assertNull(mockWebServer.takeRequest(1, TimeUnit.SECONDS))
        assertEquals(1, parseErrors.size)
        assertTrue(parseErrors.single().contains("malformed.xlf"))
    }

    private fun executeAction(
        action: TranslateWithDolocAction = TranslateWithDolocAction(),
        file: VirtualFile = xliffFile
    ) {
        val dataContext = MapDataContext()
        dataContext.put(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(file))
        dataContext.put(CommonDataKeys.PROJECT, project)

        val event = AnActionEvent.createFromDataContext("test", null, dataContext)

        ApplicationManager.getApplication().runWriteAction {
            DolocRequestBuilder.setBaseUrl(mockWebServer.url("/").toString().removeSuffix("/"))
            action.actionPerformed(event)
        }
    }

    private fun createVirtualFile(name: String, content: String): VirtualFile {
        val physicalFile = tempDir.resolve(name).toFile()
        physicalFile.parentFile?.mkdirs()
        physicalFile.writeText(content)
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(physicalFile)
            ?: error("Missing virtual file for $name")
    }

    private fun waitForTranslationResult() {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5)
        while (System.currentTimeMillis() < deadline) {
            ApplicationManager.getApplication().invokeAndWait {
                xliffFile.refresh(false, false)
            }

            if (VfsUtil.loadText(xliffFile).contains("state=\"translated\"")) {
                return
            }

            Thread.sleep(50)
        }

        assertTrue(
            "File should contain translation state='translated'",
            VfsUtil.loadText(xliffFile).contains("state=\"translated\"")
        )
    }
}
