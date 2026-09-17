package com.vibe.app.di

import com.vibe.app.feature.agent.workflow.ProjectStateRepository
import com.vibe.app.data.repository.RoomProjectStateRepository
import com.vibe.app.feature.agent.workflow.WorkflowOrchestrator
import com.vibe.app.feature.agent.workflow.WorkflowOrchestratorImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkflowModule {

    @Binds
    @Singleton
    abstract fun bindWorkflowOrchestrator(
        impl: WorkflowOrchestratorImpl
    ): WorkflowOrchestrator

    @Binds
    @Singleton
    abstract fun bindProjectStateRepository(
        impl: RoomProjectStateRepository
    ): ProjectStateRepository
}
