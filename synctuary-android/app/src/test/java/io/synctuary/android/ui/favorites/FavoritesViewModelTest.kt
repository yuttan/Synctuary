package io.synctuary.android.ui.favorites

import android.app.Application
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.synctuary.android.data.FavoritesRepository
import io.synctuary.android.data.api.dto.FavoriteItemDto
import io.synctuary.android.data.api.dto.FavoriteListDetailDto
import io.synctuary.android.data.api.dto.FavoriteListDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
class FavoritesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val mockApp = mockk<Application>(relaxed = true)
    private val mockRepo = mockk<FavoritesRepository>()

    private fun listDto(
        id: String = "list1",
        name: String = "Favorites",
        hidden: Boolean = false,
        itemCount: Int = 0,
    ) = FavoriteListDto(
        id = id, name = name, hidden = hidden, item_count = itemCount,
        created_at = 100L, modified_at = 200L,
    )

    private val sampleLists = listOf(
        listDto("a", "Public"),
        listDto("b", "Private", hidden = true, itemCount = 3),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { mockRepo.listAll(false) } returns sampleLists.filter { !it.hidden }
        coEvery { mockRepo.listAll(true) } returns sampleLists
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createVm() = FavoritesViewModel(mockApp, mockRepo)

    @Test
    fun `init loads visible lists`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(1, state.lists.size)
        assertEquals("Public", state.lists[0].name)
        assertFalse(state.loading)
        assertFalse(state.hiddenUnlocked)
    }

    @Test
    fun `onHiddenUnlocked loads all lists including hidden`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        vm.onHiddenUnlocked()
        runCurrent()

        val state = vm.uiState.value
        assertTrue(state.hiddenUnlocked)
        assertEquals(2, state.lists.size)
    }

    @Test
    fun `lockHidden hides lists again`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        vm.onHiddenUnlocked()
        runCurrent()
        assertTrue(vm.uiState.value.hiddenUnlocked)

        vm.lockHidden()
        runCurrent()

        assertFalse(vm.uiState.value.hiddenUnlocked)
        assertEquals(1, vm.uiState.value.lists.size)
    }

    @Test
    fun `onAppBackgrounded locks hidden lists`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        vm.onHiddenUnlocked()
        runCurrent()

        vm.onAppBackgrounded()
        runCurrent()

        assertFalse(vm.uiState.value.hiddenUnlocked)
    }

    @Test
    fun `onAppBackgrounded does nothing when already locked`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        vm.onAppBackgrounded()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.hiddenUnlocked)
    }

    @Test
    fun `createList calls repo and reloads`() = runTest {
        val newList = listDto("c", "New List")
        coEvery { mockRepo.createList("New List", false) } returns newList

        val vm = createVm()
        advanceUntilIdle()

        vm.createList("New List")
        advanceUntilIdle()

        coVerify { mockRepo.createList("New List", false) }
        coVerify(atLeast = 2) { mockRepo.listAll(false) }
    }

    @Test
    fun `deleteList calls repo and reloads`() = runTest {
        coEvery { mockRepo.deleteList("a") } returns Unit

        val vm = createVm()
        advanceUntilIdle()

        vm.deleteList(listDto("a", "Public"))
        advanceUntilIdle()

        coVerify { mockRepo.deleteList("a") }
    }

    @Test
    fun `toggleHidden flips the hidden flag`() = runTest {
        val updated = listDto("a", "Public", hidden = true)
        coEvery { mockRepo.updateList("a", hidden = true) } returns updated

        val vm = createVm()
        advanceUntilIdle()

        vm.toggleHidden(listDto("a", "Public", hidden = false))
        advanceUntilIdle()

        coVerify { mockRepo.updateList("a", hidden = true) }
    }

    @Test
    fun `loadListDetail sets selectedList`() = runTest {
        val detail = FavoriteListDetailDto(
            id = "a", name = "Public", hidden = false, item_count = 1,
            created_at = 100L, modified_at = 200L,
            items = listOf(FavoriteItemDto("/photo.jpg", 300L)),
        )
        coEvery { mockRepo.getList("a") } returns detail

        val vm = createVm()
        advanceUntilIdle()

        vm.loadListDetail("a")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(detail, state.selectedList)
        assertFalse(state.selectedListLoading)
    }

    @Test
    fun `clearSelectedList resets to null`() = runTest {
        val detail = FavoriteListDetailDto(
            id = "a", name = "Test", hidden = false, item_count = 0,
            created_at = 1L, modified_at = 2L, items = emptyList(),
        )
        coEvery { mockRepo.getList("a") } returns detail

        val vm = createVm()
        advanceUntilIdle()

        vm.loadListDetail("a")
        advanceUntilIdle()

        vm.clearSelectedList()
        assertNull(vm.uiState.value.selectedList)
    }

    @Test
    fun `removeItemFromList calls repo and reloads detail`() = runTest {
        val detail = FavoriteListDetailDto(
            id = "a", name = "Test", hidden = false, item_count = 1,
            created_at = 1L, modified_at = 2L,
            items = listOf(FavoriteItemDto("/file.txt", 100L)),
        )
        coEvery { mockRepo.getList("a") } returns detail
        coEvery { mockRepo.removeItem("a", "/file.txt") } returns Unit

        val vm = createVm()
        advanceUntilIdle()

        vm.removeItemFromList("a", "/file.txt")
        advanceUntilIdle()

        coVerify { mockRepo.removeItem("a", "/file.txt") }
        coVerify(atLeast = 1) { mockRepo.getList("a") }
    }

    @Test
    fun `error from repo is captured in state`() = runTest {
        coEvery { mockRepo.listAll(false) } returns emptyList()
        val vm = createVm()
        advanceUntilIdle()

        coEvery { mockRepo.createList("X", false) } throws RuntimeException("network down")
        vm.createList("X")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.error?.contains("network down") == true)
    }

    @Test
    fun `clearError resets error`() = runTest {
        coEvery { mockRepo.listAll(false) } returns emptyList()
        val vm = createVm()
        advanceUntilIdle()

        coEvery { mockRepo.createList("X", false) } throws RuntimeException("fail")
        vm.createList("X")
        advanceUntilIdle()

        vm.clearError()
        assertNull(vm.uiState.value.error)
    }
}
