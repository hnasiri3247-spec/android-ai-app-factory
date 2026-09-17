package com.vibe.app.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workflow_states")
data class WorkflowStateEntity(
    @PrimaryKey
    val projectId: String,
    val stateJson: String,
    val updatedAt: Long = System.currentTimeMillis(),
)
