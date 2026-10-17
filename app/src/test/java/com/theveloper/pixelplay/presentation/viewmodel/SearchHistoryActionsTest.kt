package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.model.SearchHistoryItem
import com.theveloper.pixelplay.data.repository.MusicRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SearchHistoryActionsTest {
    private class Storage {
        val repository = mockk<MusicRepository>(relaxed = true)
        var items = listOf(SearchHistoryItem(query = "One", timestamp = 1),
            SearchHistoryItem(query = "Two", timestamp = 2))
        init {
            coEvery { repository.getRecentSearchHistory(any()) } coAnswers { items.toList() }
            coEvery { repository.deleteSearchHistoryItemByQuery(any()) } coAnswers {
                val query = firstArg<String>()
                items = items.filterNot { it.query == query }
            }
            coEvery { repository.clearSearchHistory() } coAnswers { items = emptyList() }
        }
    }

    @Test fun removeUpdatesVisibleHistoryAndPersistsAfterReload() = runTest {
        val storage = Storage()
        val holder = SearchStateHolder(storage.repository)
        holder.initialize(backgroundScope)
        holder.loadSearchHistory()
        holder.searchHistory.first { it.size == 2 }
        holder.deleteSearchHistoryItem("One")
        assertEquals(listOf("Two"), holder.searchHistory.first { it.size == 1 }.map { it.query })
        val restored = SearchStateHolder(storage.repository)
        restored.initialize(backgroundScope)
        restored.loadSearchHistory()
        assertEquals(listOf("Two"), restored.searchHistory.first { it.size == 1 }.map { it.query })
        coVerify(exactly = 1) { storage.repository.deleteSearchHistoryItemByQuery("One") }
        assertEquals(listOf("Two"), storage.items.map { it.query })
    }

    @Test fun clearAllUpdatesVisibleHistoryAndPersistentStorage() = runTest {
        val storage = Storage()
        val holder = SearchStateHolder(storage.repository)
        holder.initialize(backgroundScope)
        holder.loadSearchHistory()
        holder.searchHistory.first { it.size == 2 }
        holder.clearSearchHistory()
        holder.searchHistory.first { it.isEmpty() }
        assertTrue(storage.items.isEmpty())
        coVerify(exactly = 1) { storage.repository.clearSearchHistory() }
    }

    @Test fun closingAnotherScreenDoesNotDisableTheActiveScreensHistory() = runTest {
        val storage = Storage()
        val active = SearchStateHolder(storage.repository)
        val otherScreen = SearchStateHolder(storage.repository)
        active.initialize(backgroundScope)
        otherScreen.initialize(backgroundScope)
        active.loadSearchHistory()
        active.searchHistory.first { it.size == 2 }
        otherScreen.onCleared()
        active.clearSearchHistory()
        active.searchHistory.first { it.isEmpty() }
        assertTrue(storage.items.isEmpty())
    }

    @Test fun delayedLoadCannotRestoreHistoryAfterClearAll() = runTest {
        val storage = Storage()
        val holder = SearchStateHolder(storage.repository)
        holder.initialize(backgroundScope)
        holder.loadSearchHistory()
        holder.searchHistory.first { it.size == 2 }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { storage.repository.getRecentSearchHistory(any()) } coAnswers {
            val snapshot = storage.items.toList()
            started.complete(Unit)
            release.await()
            snapshot
        }
        holder.loadSearchHistory()
        started.await()
        holder.clearSearchHistory()
        release.complete(Unit)
        holder.searchHistory.first { it.isEmpty() }
        assertTrue(storage.items.isEmpty())
    }
}
