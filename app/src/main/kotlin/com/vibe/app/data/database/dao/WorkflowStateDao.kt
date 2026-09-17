package com.vibe.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vibe.app.data.database.entity.WorkflowStateEntity

@Dao
interface WorkflowStateDao {

    @Query("SELECT * FROM workflow_states WHERE projectId = :projectId")
    suspend fun get(projectId: String): WorkflowStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(state: WorkflowStateEntity)

    @Query("DELETE FROM workflow_states WHERE projectId = :projectId")
    suspend fun delete(projectId: String)
}
