/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.mapper;

import java.util.List;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionSegmentDTO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 按固定执行身份读取终态和结果段完整事件。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Mapper
public interface RuntimeExecutionResultMapper {
    ExecutionSegmentDTO findSegment(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("rootEventId") String rootEventId,
            @Param("segmentId") String segmentId);

    List<CommittedEventDTO> listSegmentEvents(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("rootEventId") String rootEventId,
            @Param("segmentId") String segmentId,
            @Param("afterSeq") long afterSeq,
            @Param("limit") int limit);

    CommittedEventDTO findExecutionTerminal(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("rootEventId") String rootEventId,
            @Param("segmentId") String segmentId);
}
