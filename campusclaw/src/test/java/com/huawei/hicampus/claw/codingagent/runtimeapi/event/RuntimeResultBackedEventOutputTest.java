/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.function.Supplier;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.RuntimeSseEventVO;

import org.junit.jupiter.api.Test;

/**
 * 确认续跑仅直推预览并通过补读交付完整事件的测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeResultBackedEventOutputTest {
    @Test
    void shouldNotifyCommittedResultWithoutSendingRequiredFrameDirectly() {
        RuntimeEventStream stream = mock(RuntimeEventStream.class);
        RuntimeResultPollingService results = mock(RuntimeResultPollingService.class);
        ExecutionTargetDTO target = new ExecutionTargetDTO("session", "execution", "root", "segment");
        RuntimeResultBackedEventOutput output = new RuntimeResultBackedEventOutput(stream, target, results);
        @SuppressWarnings("unchecked")
        Supplier<RuntimeSseEventVO> frame = mock(Supplier.class);

        output.emit(frame);
        output.complete();

        verify(frame, never()).get();
        verify(stream, never()).emit(org.mockito.ArgumentMatchers.any(RuntimeSseEventVO.class));
        verify(results, org.mockito.Mockito.times(2)).notifyCommitted(target);
    }

    @Test
    void shouldSendPreviewOnlyToCurrentStream() {
        RuntimeEventStream stream = mock(RuntimeEventStream.class);
        RuntimeResultPollingService results = mock(RuntimeResultPollingService.class);
        ExecutionTargetDTO target = new ExecutionTargetDTO("session", "execution", "root", "segment");
        RuntimeResultBackedEventOutput output = new RuntimeResultBackedEventOutput(stream, target, results);
        @SuppressWarnings("unchecked")
        Supplier<RuntimeSseEventVO> preview = mock(Supplier.class);

        output.emitBestEffort(preview);

        verify(stream).emitBestEffort(preview);
        verify(results, never()).notifyCommitted(target);
    }
}
