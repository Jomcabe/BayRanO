package com.bayrano.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QueryLogDao {

    @Insert
    suspend fun insert(log: QueryLog): Long

    @Query("SELECT * FROM query_log ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int = 200): List<QueryLog>

    @Query("SELECT * FROM query_log ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<QueryLog>>

    @Query("DELETE FROM query_log")
    suspend fun clear()
}
