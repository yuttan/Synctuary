package io.synctuary.android.ui.files

import android.app.Application
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.synctuary.android.data.FileRepository
import io.synctuary.android.data.TransferState
import io.synctuary.android.data.api.dto.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val mockApp = mockk<Application>(relaxed = true)
    private val mockRepo = mockk<FileRepository>()

    private fun entry(name: String, type: String = "file") = FileEntry(
        name = name,
        type = type,
        modified_at = 1700000000L,
    )

    private val rootEntries = listOf(
        entry("photos", "dir"),
        entry("readme.txt"),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { mockRepo.listFiles("/") } returns rootEntries
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createVm() = FileBrowserViewModel(mockApp, mockRepo)

    @Test
    fun `init loads root directory`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals("/", state.currentPath)
        assertEquals(rootEntries, state.entries)
        assertFalse(state.loading)
        assertNull(state.error)
    }

    @Test
    fun `navigateInto appends to path and loads`() = runTest {
        val subEntries = listOf(entry("sunset.jpg"))
        coEvery { mockRepo.listFiles("/photos") } returns subEntries

        val vm = createVm()
        advanceUntilIdle()

        vm.navigateInto("photos")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("/photos", state.currentPath)
        assertEquals(subEntries, state.entries)
    }

    @Test
    fun `navigateUp goes to parent`() = runTest {
        coEvery { mockRepo.listFiles("/photos") } returns emptyList()

        val vm = createVm()
        advanceUntilIdle()
        vm.navigateInto("photos")
        advanceUntilIdle()

        val moved = vm.navigateUp()
        advanceUntilIdle()

        assertTrue(moved)
        assertEquals("/", vm.uiState.value.currentPath)
    }

    @Test
    fun `navigateUp returns false at root`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        assertFalse(vm.navigateUp())
    }

    @Test
    fun `toggleSearch activates and deactivates`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.searchActive)

        vm.toggleSearch()
        assertTrue(vm.uiState.value.searchActive)

        vm.setSearchQuery("photo")
        assertEquals("photo", vm.uiState.value.searchQuery)

        vm.toggleSearch()
        assertFalse(vm.uiState.value.searchActive)
        assertEquals("", vm.uiState.value.searchQuery)
    }

    @Test
    fun `selectForAction sets and clears selected entry`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        val e = entry("test.txt")
        vm.selectForAction(e)
        assertEquals(e, vm.uiState.value.selectedEntry)

        vm.selectForAction(null)
        assertNull(vm.uiState.value.selectedEntry)
    }

    @Test
    fun `deleteFile calls repo and reloads`() = runTest {
        coEvery { mockRepo.deleteFile("/readme.txt", false) } returns Unit

        val vm = createVm()
        advanceUntilIdle()

        vm.deleteFile(entry("readme.txt"))
        advanceUntilIdle()

        coVerify { mockRepo.deleteFile("/readme.txt", false) }
        coVerify(atLeast = 2) { mockRepo.listFiles("/") }
    }

    @Test
    fun `deleteFile for dir uses recursive`() = runTest {
        coEvery { mockRepo.deleteFile("/photos", true) } returns Unit

        val vm = createVm()
        advanceUntilIdle()

        vm.deleteFile(entry("photos", "dir"))
        advanceUntilIdle()

        coVerify { mockRepo.deleteFile("/photos", true) }
    }

    @Test
    fun `renameFile calls moveFile on repo`() = runTest {
        coEvery { mockRepo.moveFile("/readme.txt", "/notes.txt", false) } returns Unit

        val vm = createVm()
        advanceUntilIdle()

        vm.renameFile(entry("readme.txt"), "notes.txt")
        advanceUntilIdle()

        coVerify { mockRepo.moveFile("/readme.txt", "/notes.txt", false) }
    }

    @Test
    fun `moveFile to different directory`() = runTest {
        coEvery { mockRepo.moveFile("/readme.txt", "/photos/readme.txt", false) } returns Unit

        val vm = createVm()
        advanceUntilIdle()

        vm.moveFile(entry("readme.txt"), "/photos")
        advanceUntilIdle()

        coVerify { mockRepo.moveFile("/readme.txt", "/photos/readme.txt", false) }
    }

    @Test
    fun `loadDirectory error sets error state`() = runTest {
        coEvery { mockRepo.listFiles("/") } returns rootEntries
        val vm = createVm()
        advanceUntilIdle()

        coEvery { mockRepo.listFiles("/bad") } throws RuntimeException("not found")
        vm.navigateToBreadcrumb("/bad")
        advanceUntilIdle()

        assertEquals("not found", vm.uiState.value.error)
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun `clearError resets error`() = runTest {
        coEvery { mockRepo.listFiles("/") } returns rootEntries
        val vm = createVm()
        advanceUntilIdle()

        coEvery { mockRepo.listFiles("/bad") } throws RuntimeException("fail")
        vm.navigateToBreadcrumb("/bad")
        advanceUntilIdle()

        vm.clearError()
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `listDirectory returns entries for folder picker`() = runTest {
        val subEntries = listOf(entry("backup", "dir"))
        coEvery { mockRepo.listFiles("/data") } returns subEntries

        val vm = createVm()
        advanceUntilIdle()

        val result = vm.listDirectory("/data")
        assertEquals(subEntries, result)
    }

    @Test
    fun `dismissTransferFeedback resets both states`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        vm.dismissTransferFeedback()
        assertEquals(TransferState.Idle, vm.uiState.value.downloadState)
        assertEquals(TransferState.Idle, vm.uiState.value.uploadState)
    }
}
