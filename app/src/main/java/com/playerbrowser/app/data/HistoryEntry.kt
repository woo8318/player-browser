package com.playerbrowser.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey val url: String,
    val title: String,
    val lastVisitedAt: Long,
    val visitCount: Int
)
