package com.vibe.app.feature.agent.workflow

import com.vibe.app.data.database.entity.ChatRoomV2
import com.vibe.app.data.database.entity.MessageV2
import com.vibe.app.data.database.entity.PlatformV2
import com.vibe.app.data.repository.ChatRepository
import com.vibe.app.data.repository.ProjectRepository
import com.vibe.app.data.repository.SettingRepository
import com.vibe.app.feature.agent.service.AgentSessionManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkflowOrchestratorImpl @Inject constructor(
    private val projectAudit: ProjectAudit,
    private val stateRepository: ProjectStateRepository,
    private val projectRepository: ProjectRepository,
    private val chatRepository: ChatRepository,
    private val settingRepository: SettingRepository,
    private val agentSessionManager: AgentSessionManager,
) : WorkflowOrchestrator {

    override suspend fun start(
        projectId: String,
        userIntent: String,
    ): WorkflowResult {
        val existing = stateRepository.get(projectId)

        val audit = projectAudit.audit(
            projectId = projectId,
            existingState = existing,
        )

        val state = existing ?: MasterProjectState(
            projectId = projectId,
            goal = userIntent,
            scopeLocked = true,
            currentStage = AgentStage.AUDIT,
        )

        val nextStage = audit.resumeStage ?: when (audit.status) {
            ProjectStatus.NEW -> AgentStage.PLANNER
            ProjectStatus.EXISTING_COMPLETE -> AgentStage.DONE
            ProjectStatus.EXISTING_INCOMPLETE -> AgentStage.PLANNER
            ProjectStatus.EXISTING_BROKEN -> AgentStage.DEBUGGER
            ProjectStatus.UNKNOWN -> AgentStage.AUDIT
        }

        val auditedState = state.copy(
            auditState = audit,
            currentStage = nextStage,
            resumePoint = audit.resumeStage,
            nextRequiredAction = nextActionFor(nextStage),
        )

        if (existing == null) {
            stateRepository.create(auditedState)
        } else {
            stateRepository.update(auditedState)
        }

        if (nextStage == AgentStage.DONE) {
            return WorkflowResult(
                decision = WorkflowDecision.DONE,
                stage = AgentStage.DONE,
                state = auditedState,
            )
        }

        if (nextStage == AgentStage.AUDIT) {
            return WorkflowResult(
                decision = WorkflowDecision.BLOCK,
                stage = AgentStage.AUDIT,
                state = auditedState,
                message = "Project audit could not determine the next stage.",
            )
        }

        return executeStage(
            state = auditedState,
            stage = nextStage,
        )
    }

    override suspend fun resume(
        projectId: String,
    ): WorkflowResult {
        val state = stateRepository.get(projectId)
            ?: return WorkflowResult.blocked(
                "No persisted workflow state exists for project $projectId."
            )

        val checkpoint = stateRepository.getLatestCheckpoint(projectId)

        val stage = checkpoint?.stage
            ?: state.resumePoint
            ?: state.currentStage

        val resumed = state.copy(
            currentStage = stage,
            resumePoint = stage,
            checkpoint = checkpoint,
            nextRequiredAction = nextActionFor(stage),
        )

        stateRepository.update(resumed)

        if (stage == AgentStage.DONE) {
            return WorkflowResult(
                decision = WorkflowDecision.DONE,
                stage = stage,
                state = resumed,
            )
        }

        return executeStage(
            state = resumed,
            stage = stage,
        )
    }

    override suspend fun continueFrom(
        projectId: String,
        stage: AgentStage,
    ): WorkflowResult {
        val state = stateRepository.get(projectId)
            ?: return WorkflowResult.blocked(
                "No persisted workflow state exists for project $projectId."
            )

        val updated = state.copy(
            currentStage = stage,
            resumePoint = stage,
            nextRequiredAction = nextActionFor(stage),
        )

        stateRepository.update(updated)

        return executeStage(
            state = updated,
            stage = stage,
        )
    }

    override suspend fun handleFailure(
        projectId: String,
        stage: AgentStage,
        error: String,
    ): WorkflowResult {
        val state = stateRepository.get(projectId)
            ?: return WorkflowResult.blocked(
                "Cannot route failure without persisted project state."
            )

        val updated = state.copy(
            currentStage = AgentStage.DEBUGGER,
            failedStages = (state.failedStages + stage).distinct(),
            knownErrors = (state.knownErrors + error).distinct(),
            nextRequiredAction = "Investigate failure from $stage and repair the project.",
            resumePoint = AgentStage.DEBUGGER,
        )

        val checkpoint = ProjectCheckpoint(
            id = "${projectId}-${stage.name}-${System.currentTimeMillis()}",
            projectId = projectId,
            stage = stage,
            timestamp = System.currentTimeMillis(),
            lastAction = "Stage failed: ${stage.name}",
            nextAction = "DEBUGGER",
            knownErrors = updated.knownErrors,
            valid = false,
        )

        stateRepository.update(updated)
        stateRepository.saveCheckpoint(projectId, checkpoint)

        return WorkflowResult(
            decision = WorkflowDecision.GO_TO_STAGE,
            stage = AgentStage.DEBUGGER,
            state = updated,
        )
    }

    private suspend fun executeStage(
        state: MasterProjectState,
        stage: AgentStage,
    ): WorkflowResult {
        val project = projectRepository.fetchProject(state.projectId)
            ?: return WorkflowResult.blocked(
                "Project ${state.projectId} was not found."
            )

        val platforms = settingRepository.fetchPlatformV2s()
            .filter { it.enabled }

        if (platforms.isEmpty()) {
            return WorkflowResult.blocked(
                "No enabled AI platform is configured."
            )
        }

        val platform = platforms.first()

        val messages = chatRepository.fetchMessagesV2(project.chat.id)
        val userMessages = messages
            .filter { it.platformType == null }
            .sortedBy { it.createdAt }

        val assistantMessages = userMessages.map { userMessage ->
            messages
                .filter {
                    it.platformType != null &&
                        (it.linkedMessageId == userMessage.id ||
                            it.createdAt >= userMessage.createdAt)
                }
                .sortedBy { it.createdAt }
                .take(1)
        }

        val context = StageContext(
            stage = stage,
            goal = state.goal,
            scope = state.scope,
            constraints = state.constraints,
            previousFindings = state.auditState.findings + state.knownErrors,
            nextRequiredAction = nextActionFor(stage),
        )

        val stagePrompt = buildStagePrompt(context)

        val session = agentSessionManager.startSession(
            chatId = project.chat.id,
            projectId = state.projectId,
            platform = platform,
            userMessages = userMessages,
            assistantMessages = assistantMessages,
            systemPrompt = stagePrompt,
            diagnosticContext = null,
            chatRoom = project.chat,
            chatPlatformModels = chatRepository.fetchChatPlatformModels(project.chat.id),
        )

        session.job.join()

        val success = session.status.value ==
            AgentSessionStatus.COMPLETED

        if (!success) {
            return handleFailure(
                projectId = state.projectId,
                stage = stage,
                error = "Agent session failed during $stage.",
            )
        }

        return completeStage(
            state = state,
            stage = stage,
        )
    }

    private suspend fun completeStage(
        state: MasterProjectState,
        stage: AgentStage,
    ): WorkflowResult {
        val nextStage = when (stage) {
            AgentStage.PLANNER -> AgentStage.UI_UX
            AgentStage.UI_UX -> AgentStage.CODER
            AgentStage.CODER -> AgentStage.REVIEWER
            AgentStage.REVIEWER -> AgentStage.BUILD
            AgentStage.BUILD -> AgentStage.TESTER
            AgentStage.DEBUGGER -> AgentStage.CODER
            AgentStage.TESTER -> AgentStage.DONE
            AgentStage.AUDIT -> AgentStage.PLANNER
            AgentStage.DONE -> AgentStage.DONE
        }

        val completed = (state.completedStages + stage).distinct()

        val updated = state.copy(
            currentStage = nextStage,
            completedStages = completed,
            resumePoint = nextStage,
            nextRequiredAction = nextActionFor(nextStage),
            checkpoint = ProjectCheckpoint(
                id = "${state.projectId}-${stage.name}-${System.currentTimeMillis()}",
                projectId = state.projectId,
                stage = stage,
                timestamp = System.currentTimeMillis(),
                lastAction = "Stage completed: ${stage.name}",
                nextAction = nextStage.name,
                valid = true,
            ),
        )

        stateRepository.update(updated)
        stateRepository.saveCheckpoint(
            state.projectId,
            updated.checkpoint!!,
        )

        return WorkflowResult(
            decision = if (nextStage == AgentStage.DONE) {
                WorkflowDecision.DONE
            } else {
                WorkflowDecision.CONTINUE
            },
            stage = nextStage,
            state = updated,
        )
    }

    private fun buildStagePrompt(context: StageContext): String {
        return """
            You are executing exactly one controlled workflow stage.

            PROJECT GOAL:
            ${context.goal}

            LOCKED SCOPE:
            ${context.scope.joinToString("\n")}

            CONSTRAINTS:
            ${context.constraints.joinToString("\n")}

            CURRENT STAGE:
            ${context.stage}

            REQUIRED ACTION:
            ${context.nextRequiredAction}

            PREVIOUS FINDINGS:
            ${context.previousFindings.joinToString("\n")}

            Rules:
            - Execute only the current stage.
            - Do not skip ahead to later stages.
            - Do not change the project goal or scope.
            - Use the available tools to perform real work.
            - Report clearly whether this stage succeeded or failed.
        """.trimIndent()
    }

    private fun nextActionFor(stage: AgentStage): String =
        when (stage) {
            AgentStage.AUDIT -> "Audit the project."
            AgentStage.PLANNER -> "Create or restore the project plan."
            AgentStage.UI_UX -> "Define the required UI/UX."
            AgentStage.CODER -> "Implement the planned changes."
            AgentStage.REVIEWER -> "Review implementation against the locked goal."
            AgentStage.BUILD -> "Build the Android project."
            AgentStage.DEBUGGER -> "Diagnose and repair the latest failure."
            AgentStage.TESTER -> "Run functional validation."
            AgentStage.DONE -> "Project is complete."
        }
}

data class WorkflowResult(
    val decision: WorkflowDecision,
    val stage: AgentStage,
    val state: MasterProjectState? = null,
    val message: String? = null,
) {
    companion object {
        fun blocked(message: String) = WorkflowResult(
            decision = WorkflowDecision.BLOCK,
            stage = AgentStage.AUDIT,
            message = message,
        )
    }
}
