package com.vibe.app.feature.agent.workflow

interface WorkflowOrchestrator {

    suspend fun start(
        projectId: String,
        userIntent: String,
    ): WorkflowResult

    suspend fun resume(
        projectId: String,
    ): WorkflowResult

    suspend fun continueFrom(
        projectId: String,
        stage: AgentStage,
    ): WorkflowResult

    suspend fun handleFailure(
        projectId: String,
        stage: AgentStage,
        error: String,
    ): WorkflowResult
}
