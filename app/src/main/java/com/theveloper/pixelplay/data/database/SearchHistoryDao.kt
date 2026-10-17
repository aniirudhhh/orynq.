package com.theveloper.pixelplay.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.theveloper.pixelplay.data.model.SearchHistoryItem
import com.theveloper.pixelplay.data.model.SearchHistoryPolicy

@Dao
interface SearchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SearchHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SearchHistoryEntity>)

    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentSearches(limit: Int): List<SearchHistoryEntity>

    @Query("DELETE FROM search_history WHERE query = :query COLLATE NOCASE")
    suspend fun deleteByQuery(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearAll()

    @Query("SELECT * FROM search_history ORDER BY timestamp DESC")
    suspend fun getAll(): List<SearchHistoryEntity>

    @Transaction
    suspend fun record(query: String, timestamp: Long) {
        val normalized = SearchHistoryPolicy.normalize(query)
        if (normalized.isEmpty()) return
        val history = SearchHistoryPolicy.recent(
            listOf(SearchHistoryItem(query = normalized, timestamp = timestamp)) +
                getAll().map { it.toSearchHistoryItem() }
        )
        replaceAll(history.map { it.toEntity() })
    }

    @Transaction
    suspend fun replaceAll(items: List<SearchHistoryEntity>) {
        clearAll()
        if (items.isNotEmpty()) insertAll(items)
    }
}
