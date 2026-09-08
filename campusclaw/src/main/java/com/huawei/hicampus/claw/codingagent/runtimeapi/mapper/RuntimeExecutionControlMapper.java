/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.mapper;

import java.time.OffsetDateTime;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedTerminalDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionSegmentDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionStateDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionTargetDTO;

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

    int markStopping(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("segmentId") String segmentId,
            @Param("stopEventId") String stopEventId,
            @Param("updatedAt") OffsetDateTime updatedAt);

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
