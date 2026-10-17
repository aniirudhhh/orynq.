package com.theveloper.pixelplay.data.model

import org.junit.Assert.*
import org.junit.Test

class SearchHistoryPolicyTest {
    private fun item(query: String, time: Long) = SearchHistoryItem(query = query, timestamp = time)

    @Test fun whitespaceAndCaseDuplicatesKeepTheLatestSpelling() {
        val result = SearchHistoryPolicy.recent(listOf(
            item("  arijit   singh ", 1), item("Arijit Singh", 3), item("Other", 2)
        ))
        assertEquals(listOf("Arijit Singh", "Other"), result.map { it.query })
    }

    @Test fun blanksNeverBecomeHistoryEntries() {
        assertTrue(SearchHistoryPolicy.recent(listOf(item(" \n\t ", 1))).isEmpty())
    }

    @Test fun twentyMostRecentUniqueQueriesAreRetained() {
        val result = SearchHistoryPolicy.recent((1L..30L).map { item("Song $it", it) })
        assertEquals(20, result.size)
        assertEquals("Song 30", result.first().query)
        assertEquals("Song 11", result.last().query)
    }

    @Test fun repeatedQueryMovesToFrontWithoutTakingAnotherSlot() {
        val previous = (1L..20L).map { item("Song $it", it) }
        val result = SearchHistoryPolicy.recent(listOf(item("SONG 1", 21)) + previous)
        assertEquals(20, result.size)
        assertEquals("SONG 1", result.first().query)
        assertEquals(1, result.count { it.query.equals("Song 1", ignoreCase = true) })
    }

    @Test fun tiedTimestampsKeepNewlySubmittedQueryFirst() {
        val result = SearchHistoryPolicy.recent(listOf(item("New", 4), item("Old", 4)))
        assertEquals(listOf("New", "Old"), result.map { it.query })
    }

    @Test fun restoredHistoryHonoursSmallerLimitsAndUnicodeCase() {
        val entries = listOf(item("ÉTÉ", 1), item("été", 2), item("Other", 3))
        assertEquals(listOf("Other", "été"), SearchHistoryPolicy.recent(entries).map { it.query })
        assertEquals(1, SearchHistoryPolicy.recent(entries, 1).size)
        assertTrue(SearchHistoryPolicy.recent(entries, 0).isEmpty())
    }
}
