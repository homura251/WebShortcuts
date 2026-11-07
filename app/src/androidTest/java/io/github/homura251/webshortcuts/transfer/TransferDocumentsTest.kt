package io.github.homura251.webshortcuts.transfer

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasType
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.homura251.webshortcuts.MainActivity
import io.github.homura251.webshortcuts.MainViewModel
import io.github.homura251.webshortcuts.WebShortcutsApplication
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.hamcrest.Matchers.allOf

/** Exercises the production Activity's URI callbacks while Espresso stubs the system picker UI. */
class TransferDocumentsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as WebShortcutsApplication
    private val resolver get() = application.contentResolver
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() = runBlocking {
        application.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        application.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        application.container.repository.load()
        resolver.call(uri("input.html"), "reset", null, null)
        Intents.init()
    }

    @After fun tearDown() { scenario?.close(); Intents.release(); resolver.call(uri("input.html"), "reset", null, null) }

    @Test fun importsAContentUriThenExportsBothFormatsThroughCreateDocument() {
        resolver.call(uri("input.html"), "seed", null, null)
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK,
            Intent().setData(uri("input.html")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        openTransfer()
        compose.onNodeWithTag("choose_import").performClick()
        await("confirm_import")
        compose.onNodeWithTag("confirm_import").performClick()
        await("transfer_result")
        compose.onNodeWithTag("transfer_result").assertTextContains("已新增 1 条收藏", substring = true)
        assertEquals("导入 <标题>", application.container.repository.bookmarks.value.single().title)
        assertNull(application.container.repository.bookmarks.value.single().autoImagePath)
        compose.onNodeWithText("完成").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("transfer_sheet").fetchSemanticsNodes().isEmpty() }

        for (format in listOf("html", "zip")) {
            intending(allOf(hasAction(Intent.ACTION_CREATE_DOCUMENT), hasType(if (format == "zip") "application/zip" else "text/html"))).respondWith(ActivityResult(Activity.RESULT_OK,
                Intent().setData(uri("export.$format")).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)))
            openTransfer()
            compose.onNodeWithTag("export_$format").performClick()
            await("transfer_result")
            compose.onNodeWithTag("transfer_result").assertTextContains("已导出 1 条收藏", substring = true)
            val bytes = resolver.openInputStream(uri("export.$format"))!!.use { it.readBytes() }
            assertTrue(bytes.isNotEmpty())
            if (format == "html") assertTrue(bytes.toString(Charsets.UTF_8).contains("导入 &lt;标题&gt;"))
            else ZipInputStream(bytes.inputStream()).use { zip ->
                val entries = mutableMapOf<String, String>()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
                assertNotNull(entries["manifest.json"]); assertNotNull(entries["appearance.json"])
                assertTrue(entries.getValue("bookmarks.json").contains("导入 <标题>"))
            }
            compose.onNodeWithText("完成").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("transfer_sheet").fetchSemanticsNodes().isEmpty() }
        }
    }

    @Test fun cancellingEitherSystemPickerLeavesTheCollectionUnchanged() {
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        openTransfer()
        compose.onNodeWithTag("choose_import").performClick()
        awaitCancelledPicker(Intent.ACTION_OPEN_DOCUMENT)
        compose.onNodeWithTag("export_html").performClick()
        awaitCancelledPicker(Intent.ACTION_CREATE_DOCUMENT)
        assertTrue(application.container.repository.bookmarks.value.isEmpty())
        compose.onNodeWithTag("choose_import").assertIsDisplayed()
    }

    private fun openTransfer() {
        var main: MainViewModel? = null
        scenario!!.onActivity { main = ViewModelProvider(it)[MainViewModel::class.java] }
        compose.waitUntil(5000) { main?.state?.value?.loading == false }
        compose.onNodeWithContentDescription("更多选项").performClick()
        compose.onNodeWithTag("open_transfer").performClick()
        await("choose_import")
    }
    private fun awaitCancelledPicker(action: String) {
        compose.waitUntil(10_000) { Intents.getIntents().any { it.action == action } &&
            compose.onAllNodesWithTag("choose_import").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun await(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun uri(name: String) = Uri.parse("content://io.github.homura251.webshortcuts.test.transfer/$name")
}
