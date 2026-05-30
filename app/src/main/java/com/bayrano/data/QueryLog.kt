package com.bayrano.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One logged assistant exchange. */
@Entity(tableName = "query_log")
data class QueryLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val question: String,
    val model: String,
    val answer: String,
    val latencyMs: Long,
    val hadImage: Boolean,
    /** Non-null if the call failed (e.g. "offline", an API error message). */
    val error: String? = null,
)
