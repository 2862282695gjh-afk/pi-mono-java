/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.campusclaw.codingagent.runtimeapi.dto.CommittedTerminalDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionControlSignalDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionSegmentDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionStateDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.campusclaw.codingagent.runtimeapi.dto.PlanReviewDecisionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.QuestionAnswerDecisionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ToolConfirmationDecisionDTO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 固定消息执行、续跑段和公共事件关联的数据库 Mapper。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Mapper
public interface RuntimeExecutionControlMapper {
    int insertExecution(ExecutionStateDTO execution);

    int insertSegment(ExecutionSegmentDTO segment);

    ExecutionStateDTO findExecution(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("rootEventId") String rootEventId,
            @Param("segmentId") String segmentId);

    ExecutionStateDTO findExecutionIdentity(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("rootEventId") String rootEventId);

    ExecutionStateDTO lockExecution(@Param("sessionId") String sessionId, @Param("executionId") String executionId);

    ExecutionStateDTO lockCurrentExecution(@Param("sessionId") String sessionId);

    ExecutionSegmentDTO lockSegment(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("segmentId") String segmentId);

    int insertSegmentEvent(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("segmentId") String segmentId,
            @Param("eventId") String eventId,
            @Param("eventSeq") long eventSeq);

    int closeSegment(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("segmentId") String segmentId,
            @Param("terminalEventId") String terminalEventId,
            @Param("terminalEventSeq") long terminalEventSeq,
            @Param("terminalReason") String terminalReason,
            @Param("updatedAt") OffsetDateTime updatedAt);

    int markConfirming(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("segmentId") String segmentId,
            @Param("toolCallId") String toolCallId,
            @Param("updatedAt") OffsetDateTime updatedAt);

    int markPlanConfirming(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("segmentId") String segmentId,
            @Param("toolCallId") String toolCallId,
            @Param("planEventId") String planEventId,
            @Param("updatedAt") OffsetDateTime updatedAt);

    int markQuestionConfirming(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("segmentId") String segmentId,
            @Param("toolCallId") String toolCallId,
            @Param("questionEventId") String questionEventId,
            @Param("updatedAt") OffsetDateTime updatedAt);

    int markStopping(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("segmentId") String segmentId,
            @Param("stopEventId") String stopEventId,
            @Param("updatedAt") OffsetDateTime updatedAt);

    int nextSegmentOrdinal(@Param("sessionId") String sessionId, @Param("executionId") String executionId);

    int insertConfirmation(ToolConfirmationDecisionDTO decision);

    int insertPlanConfirmation(PlanReviewDecisionDTO decision);

    int insertQuestionAnswer(QuestionAnswerDecisionDTO decision);

    int resumeAfterConfirmation(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("previousSegmentId") String previousSegmentId,
            @Param("segmentId") String segmentId,
            @Param("toolCallId") String toolCallId,
            @Param("updatedAt") OffsetDateTime updatedAt);

    int resumeAfterPlanConfirmation(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("previousSegmentId") String previousSegmentId,
            @Param("segmentId") String segmentId,
            @Param("toolCallId") String toolCallId,
            @Param("planEventId") String planEventId,
            @Param("updatedAt") OffsetDateTime updatedAt);

    int resumeAfterQuestionAnswer(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("previousSegmentId") String previousSegmentId,
            @Param("segmentId") String segmentId,
            @Param("toolCallId") String toolCallId,
            @Param("questionEventId") String questionEventId,
            @Param("updatedAt") OffsetDateTime updatedAt);

    int claimConfirmation(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("rootEventId") String rootEventId,
            @Param("previousSegmentId") String previousSegmentId,
            @Param("toolCallId") String toolCallId,
            @Param("claimedAt") OffsetDateTime claimedAt);

    ToolConfirmationDecisionDTO findClaimedConfirmation(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("previousSegmentId") String previousSegmentId,
            @Param("toolCallId") String toolCallId);

    int claimPlanConfirmation(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("rootEventId") String rootEventId,
            @Param("previousSegmentId") String previousSegmentId,
            @Param("planEventId") String planEventId,
            @Param("claimedAt") OffsetDateTime claimedAt);

    PlanReviewDecisionDTO findClaimedPlanConfirmation(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("previousSegmentId") String previousSegmentId,
            @Param("planEventId") String planEventId);

    int claimQuestionAnswer(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("rootEventId") String rootEventId,
            @Param("previousSegmentId") String previousSegmentId,
            @Param("questionEventId") String questionEventId,
            @Param("claimedAt") OffsetDateTime claimedAt);

    QuestionAnswerDecisionDTO findClaimedQuestionAnswer(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("previousSegmentId") String previousSegmentId,
            @Param("questionEventId") String questionEventId);

    int acknowledgeConfirmation(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("confirmationEventId") String confirmationEventId,
            @Param("completedAt") OffsetDateTime completedAt);

    int acknowledgePlanConfirmation(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("confirmationEventId") String confirmationEventId,
            @Param("completedAt") OffsetDateTime completedAt);

    int acknowledgeQuestionAnswer(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("answerEventId") String answerEventId,
            @Param("completedAt") OffsetDateTime completedAt);

    List<ExecutionControlSignalDTO> findPendingControls(@Param("targets") List<ExecutionTargetDTO> targets);

    int markTerminal(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("segmentId") String segmentId,
            @Param("terminalEventId") String terminalEventId,
            @Param("terminalReason") String terminalReason,
            @Param("terminalAt") OffsetDateTime terminalAt);

    CommittedTerminalDTO findCommittedTerminal(
            @Param("target") ExecutionTargetDTO target,
            @Param("terminalEventId") String terminalEventId,
            @Param("terminalReason") String terminalReason);
}
