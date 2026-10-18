package com.wuming.android.tv.db.dao
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.wuming.android.tv.db.SearchHistory

@Dao
interface SearchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(history: SearchHistory): Long

    @Update fun update(history: SearchHistory)

    @Delete fun delete(history: SearchHistory)

    @Query("DELETE FROM SearchHistory")
    fun deleteAll()

    @Query("SELECT * FROM SearchHistory ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int): List<SearchHistory>

    @Query("SELECT * FROM SearchHistory ORDER BY frequency DESC, timestamp DESC LIMIT :limit")
    fun getFrequent(limit: Int): List<SearchHistory>

    @Query("SELECT * FROM SearchHistory WHERE keyword = :keyword LIMIT 1")
    fun findByKeyword(keyword: String?): SearchHistory?

    fun insertOrUpdate(keyword: String) {
        val existing = findByKeyword(keyword)
        if (existing != null) {
            existing.frequency = existing.frequency + 1
            existing.timestamp = System.currentTimeMillis()
            update(existing)
        } else {
            insert(SearchHistory(keyword))
        }
    }
}
