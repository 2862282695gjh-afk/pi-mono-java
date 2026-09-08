/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.persistence;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionSegmentDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.SegmentEventBatchDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.mapper.RuntimeExecutionResultMapper;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeExecutionSegmentState;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 使用 MyBatis 按固定执行身份补读权威结果。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Repository
public class MyBatisRuntimeExecutionResultRepository implements RuntimeExecutionResultRepository {
    private final RuntimeExecutionResultMapper mapper;

    public MyBatisRuntimeExecutionResultRepository(RuntimeExecutionResultMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SegmentEventBatchDTO> readSegmentEvents(ExecutionTargetDTO target, long afterSeq, int limit) {
        requireTarget(target);
        if (afterSeq < 0 || limit < 1) {
            throw new IllegalArgumentException("segment event range is invalid");
        }
        List<CommittedEventDTO> events = mapper.listSegmentEvents(
                target.sessionId(), target.executionId(), target.rootEventId(), target.segmentId(), afterSeq, limit);
        ExecutionSegmentDTO segment =
                mapper.findSegment(target.sessionId(), target.executionId(), target.rootEventId(), target.segmentId());
        if (segment == null) {
            return Optional.empty();
        }
        var result = new SegmentEventBatchDTO();
        result.setEvents(List.copyOf(events));
        result.setTerminal(terminalReached(segment, afterSeq, events));
        return Optional.of(result);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CommittedEventDTO> findExecutionTerminal(ExecutionTargetDTO target) {
        requireTarget(target);
        return Optional.ofNullable(mapper.findExecutionTerminal(
                target.sessionId(), target.executionId(), target.rootEventId(), target.segmentId()));
    }

    private static boolean terminalReached(ExecutionSegmentDTO segment, long afterSeq, List<CommittedEventDTO> events) {
        if (segment.getState() != RuntimeExecutionSegmentState.CLOSED || segment.getTerminalEventSeq() == null) {
            return false;
        }
        long deliveredSeq = events.isEmpty() ? afterSeq : events.getLast().getEventSeq();
        return deliveredSeq >= segment.getTerminalEventSeq();
    }

    private static void requireTarget(ExecutionTargetDTO target) {
        Objects.requireNonNull(target, "target");
        if (isBlank(target.sessionId())
                || isBlank(target.executionId())
                || isBlank(target.rootEventId())
                || isBlank(target.segmentId())) {
            throw new IllegalArgumentException("execution target is incomplete");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
