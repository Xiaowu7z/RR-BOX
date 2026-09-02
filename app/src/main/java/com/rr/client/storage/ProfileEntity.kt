package com.rr.client.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val subscriptionUrl: String,
    val lastUpdated: Long,
    val uploadBytes: Long,
    val downloadBytes: Long,
    val totalBytes: Long,
    val expireTime: Long,
    val nodesJson: String
)

@Entity(tableName = "traffic_history")
data class TrafficHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nodeTag: String,
    val proxyDownload: Long,
    val proxyUpload: Long,
    val directDownload: Long,
    val directUpload: Long,
    val durationSeconds: Long,
    val timestamp: Long
)
