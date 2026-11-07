package io.github.homura251.webshortcuts

import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.homura251.webshortcuts.data.BookmarkItem
import io.github.homura251.webshortcuts.data.IconSourceMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowShortcutManager

/** Refresh feedback follows committed files, not simply a successful HTTP response. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = WebShortcutsApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RefreshViewModelTest {
    private lateinit var application: WebShortcutsApplication
    private lateinit var server: MockWebServer
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        application = ApplicationProvider.getApplicationContext()
        application.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        application.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        server = MockWebServer().also { it.start() }
    }

    @After fun tearDown() {
        stores.forEach(ViewModelStore::clear)
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test fun failedRefreshPreservesTheImageSourceAndLastSuccessfulFetchTime() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(404)
        }
        val original = seed("/missing")
        val vm = createViewModel()
        vm.refresh(original)
        withTimeout(5_000) { vm.state.first { original.id in it.refreshMessages && original.id !in it.refreshingIds } }
        val unchanged = application.container.repository.find(original.id)!!
        assertEquals(original.autoImagePath, unchanged.autoImagePath)
        assertEquals(original.sourceUrl, unchanged.sourceUrl)
        assertEquals(original.lastFetchedAt, unchanged.lastFetchedAt)
        assertEquals(original.imageVersion, unchanged.imageVersion)
        assertTrue(vm.state.value.refreshMessages.getValue(original.id).contains("保留原图"))
        val bitmap = application.container.iconStore.read(unchanged.autoImagePath)!!
        try { assertEquals(Color.RED, bitmap.getPixel(256, 256)) } finally { bitmap.recycle() }
    }

    @Test fun fetchStartedBeforeImportCannotOverwriteARestoredMatchingIdUrlAndVersion() = runBlocking {
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val image = png(Color.GREEN)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/restore-race" -> html("/icon.png")
                "/icon.png" -> {
                    requested.countDown()
                    if (release.await(5, TimeUnit.SECONDS)) imageResponse(image) else MockResponse().setResponseCode(503)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val original = seed("/restore-race")
        val vm = createViewModel()
        vm.refresh(original)
        try {
            assertTrue(withContext(Dispatchers.IO) { requested.await(5, TimeUnit.SECONDS) })
            val gate = application.container.transferGate
            val lease = gate.begin()!!
            val restored = original.copy(title = "Restored", sourceUrl = "https://restored.test/icon.png")
            application.container.mutationMutex.withLock { application.container.repository.replaceAll(listOf(restored)) }
            gate.end(lease)
            release.countDown()
            withTimeout(5000) { vm.state.first { original.id !in it.refreshingIds } }
            assertEquals(restored, application.container.repository.find(original.id))
        } finally { release.countDown() }
    }

    @Test fun lateRefreshPreservesTagsAddedWhileTheIconWasDownloading() = runBlocking {
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val image = png(Color.GREEN)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/tag-race" -> html("/icon.png")
                "/icon.png" -> {
                    requested.countDown()
                    if (release.await(5, TimeUnit.SECONDS)) imageResponse(image) else MockResponse().setResponseCode(503)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val original = seed("/tag-race")
        val vm = createViewModel()
        vm.refresh(original)
        try {
            assertTrue(withContext(Dispatchers.IO) { requested.await(5, TimeUnit.SECONDS) })
            val repository = application.container.repository
            application.container.mutationMutex.withLock {
                repository.applyTags(setOf(original.id), io.github.homura251.webshortcuts.data.TagChanges(newNames = listOf("Added during fetch")))
            }
            val tag = repository.library.value.tags.single()
            release.countDown()
            withTimeout(5000) { vm.state.first { original.id !in it.refreshingIds } }
            val actual = repository.find(original.id)!!
            assertEquals(setOf(tag.id), actual.tagIds)
            assertEquals(original.imageVersion + 1, actual.imageVersion)
        } finally { release.countDown() }
    }

    @Test fun repeatedClicksShareOneRequestAndRowFeedbackTracksTheActualWork() = runBlocking {
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val imageRequests = AtomicInteger()
        val image = png(Color.GREEN)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/site" -> html("/icon.png")
                "/icon.png" -> {
                    imageRequests.incrementAndGet()
                    requested.countDown()
                    if (release.await(5, TimeUnit.SECONDS)) imageResponse(image) else MockResponse().setResponseCode(503)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        try {
            val original = seed("/site")
            val vm = createViewModel()
            vm.refresh(original)
            assertTrue(withContext(Dispatchers.IO) { requested.await(5, TimeUnit.SECONDS) })
            assertEquals(setOf(original.id), vm.state.value.refreshingIds)
            assertTrue(vm.state.value.refreshMessages.getValue(original.id).contains("正在"))
            vm.refresh(original)
            vm.refresh(original)
            assertEquals(1, imageRequests.get())
            release.countDown()
            withTimeout(5_000) { vm.state.first { original.id !in it.refreshingIds } }
            val updated = application.container.repository.find(original.id)!!
            assertEquals(2, server.requestCount)
            assertEquals(1, imageRequests.get())
            assertEquals(original.imageVersion + 1, updated.imageVersion)
            assertTrue(updated.lastFetchedAt!! > original.lastFetchedAt!!)
            assertTrue(updated.sourceUrl!!.endsWith("/icon.png"))
            assertEquals("已更新图标，保存在此设备", vm.state.value.refreshMessages[original.id])
        } finally { release.countDown() }
    }

    @Test fun batchReportsCompletedCountsAndRetainsAFailedRowsOldSyncTime() = runBlocking {
        val firstRequested = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondRequested = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val image = png(Color.GREEN)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/first" -> html("/first.png")
                "/second" -> html("/second.png")
                "/first.png" -> {
                    firstRequested.countDown()
                    if (releaseFirst.await(5, TimeUnit.SECONDS)) imageResponse(image) else MockResponse().setResponseCode(503)
                }
                "/second.png" -> {
                    secondRequested.countDown()
                    releaseSecond.await(5, TimeUnit.SECONDS)
                    MockResponse().setResponseCode(404)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        try {
            // New records are prepended. The successful record must be first in this batch.
            val failed = seed("/second")
            val success = seed("/first")
            val custom = seed("/custom", IconSourceMode.CUSTOM)
            val vm = createViewModel()
            vm.refreshAll()
            assertTrue(withContext(Dispatchers.IO) { firstRequested.await(5, TimeUnit.SECONDS) })
            assertTrue(vm.state.value.refreshing)
            assertEquals(2, vm.state.value.refreshTotal)
            assertEquals(0, vm.state.value.refreshCompleted)
            assertEquals(setOf(success.id), vm.state.value.refreshingIds)
            releaseFirst.countDown()
            assertTrue(withContext(Dispatchers.IO) { secondRequested.await(5, TimeUnit.SECONDS) })
            assertEquals(1, vm.state.value.refreshCompleted)
            assertEquals(setOf(failed.id), vm.state.value.refreshingIds)
            releaseSecond.countDown()
            withTimeout(5_000) { vm.state.first { !it.refreshing } }
            assertEquals(2, vm.state.value.refreshCompleted)
            assertTrue(vm.state.value.refreshingIds.isEmpty())
            assertTrue(vm.state.value.refreshSummary!!.contains("已更新 1 个图标，1 个保留原图"))
            assertEquals(failed.lastFetchedAt, application.container.repository.find(failed.id)!!.lastFetchedAt)
            assertEquals(custom, application.container.repository.find(custom.id))
            assertFalse(custom.id in vm.state.value.refreshMessages)
            vm.clearRefreshSummary()
            assertNull(vm.state.value.refreshSummary)
        } finally { releaseFirst.countDown(); releaseSecond.countDown() }
    }

    @Test
    @Config(shadows = [RateLimitedShortcutManager::class])
    fun launcherRejectionDoesNotTurnASavedImageIntoADownloadFailure() = runBlocking {
        val image = png(Color.GREEN)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/site" -> html("/icon.png")
                "/icon.png" -> imageResponse(image)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val original = seed("/site")
        val shortcuts = application.getSystemService(ShortcutManager::class.java)
        Shadows.shadowOf(shortcuts).setIsRequestPinShortcutSupported(true)
        assertTrue(application.container.shortcutPublisher.pin(original))
        assertTrue(application.container.shortcutPublisher.isPinned(original.id))
        val vm = createViewModel()
        vm.refresh(original)
        withTimeout(5_000) { vm.state.first { original.id in it.refreshMessages && original.id !in it.refreshingIds } }
        val updated = application.container.repository.find(original.id)!!
        assertTrue(updated.lastFetchedAt!! > original.lastFetchedAt!!)
        assertNotEquals(original.autoImagePath, updated.autoImagePath)
        assertEquals("图标已更新，桌面同步待重试", vm.state.value.refreshMessages[original.id])
    }

    @Test fun cancellingTheViewModelClearsLiveRowStateWithoutChangingSuccessfulSyncMetadata() = runBlocking {
        val requested = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/site" -> html("/icon.png")
                "/icon.png" -> { requested.countDown(); MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val original = seed("/site")
        val vm = createViewModel()
        vm.refresh(original)
        assertTrue(withContext(Dispatchers.IO) { requested.await(5, TimeUnit.SECONDS) })
        assertEquals(setOf(original.id), vm.state.value.refreshingIds)
        stores.single().clear()
        withTimeout(5_000) { vm.state.first { it.refreshingIds.isEmpty() } }
        assertTrue(vm.state.value.refreshMessages.getValue(original.id).contains("已停止"))
        assertEquals(original, application.container.repository.find(original.id))
    }

    private suspend fun createViewModel(): MainViewModel {
        val store = ViewModelStore().also(stores::add)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(application, SavedStateHandle()) as T
        }
        return ViewModelProvider(store, factory)[MainViewModel::class.java].also { vm ->
            withTimeout(5_000) { vm.state.first { !it.loading } }
        }
    }

    private suspend fun seed(path: String, mode: IconSourceMode = IconSourceMode.AUTO): BookmarkItem {
        val item = BookmarkItem(title = path, url = server.url(path).toString(), selectedIconSource = mode,
            sourceUrl = server.url("/old.png").toString(), lastFetchedAt = 1234L)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val imagePath = try { application.container.iconStore.save(item.id, 1, IconSourceMode.AUTO, bitmap) }
            finally { bitmap.recycle() }
        return item.copy(autoImagePath = imagePath).also { application.container.repository.save(it) }
    }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
            finally { bitmap.recycle() }
    }

    private fun html(icon: String) = MockResponse().setHeader("Content-Type", "text/html")
        .setBody("""<head><link rel="icon" sizes="512x512" href="$icon"></head>""")
    private fun imageResponse(bytes: ByteArray) = MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes))

    @Implements(ShortcutManager::class)
    class RateLimitedShortcutManager : ShadowShortcutManager() {
        @Implementation override fun isRateLimitingActive(): Boolean = true
    }
}
