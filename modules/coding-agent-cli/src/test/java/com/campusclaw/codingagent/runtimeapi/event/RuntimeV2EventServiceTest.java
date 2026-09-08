/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.runtimeapi.dto.AcceptedControlDTO;
import com.campusclaw.codingagent.runtimeapi.dto.AcceptedControlStreamDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionControlDispatcher;
import com.campusclaw.codingagent.runtimeapi.session.ToolConfirmationResult;
import com.campusclaw.codingagent.runtimeapi.vo.SubmitSessionEventRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.UserInterruptEventRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.UserMessageContentRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.UserMessageEventRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.UserToolConfirmationEventRequestVO;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Events v2 联合请求分派与本机控制快路径测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeV2EventServiceTest {
    private static final MateCredentials CREDENTIALS = MateCredentials.jwt("credential", "token", "access-token");

    @Test
    void shouldSubmitMessageWithOriginalCredentials() {
        Fixture fixture = fixture();
        UserMessageEventRequestVO message = new UserMessageEventRequestVO();
        UserMessageContentRequestVO.TextRequestVO text = new UserMessageContentRequestVO.TextRequestVO();
        text.setText("question");
        message.setContent(List.of(text));
        RuntimeEventStream stream = mock(RuntimeEventStream.class);
        when(fixture.messages.submit("session", message, Locale.US, CREDENTIALS))
                .thenReturn(stream);

        assertThat(fixture.service.submit("session", new SubmitSessionEventRequestVO(message), Locale.US, CREDENTIALS))
                .isSameAs(stream);
    }

    @Test
    void shouldAcceptInterruptBeforeDispatchingExactPersistedTarget() {
        Fixture fixture = fixture();
        RuntimeEventStream stream = mock(RuntimeEventStream.class);
        ExecutionTargetDTO target = target("segment-1");
        UserInterruptEventRequestVO interrupt = new UserInterruptEventRequestVO();
        interrupt.setTargetEventId("root");
        when(fixture.controls.acceptInterrupt("session", "root"))
                .thenReturn(new AcceptedControlStreamDTO(stream, new AcceptedControlDTO(null, target)));

        assertThat(fixture.service.submit(
                        "session", new SubmitSessionEventRequestVO(interrupt), Locale.US, CREDENTIALS))
                .isSameAs(stream);

        verify(fixture.dispatcher).dispatchStop(target);
    }

    @Test
    void shouldStageWaiterBackedStreamBeforeDispatchingConfirmation() {
        Fixture fixture = fixture();
        RuntimeEventStream stream = mock(RuntimeEventStream.class);
        ExecutionTargetDTO confirming = target("segment-1");
        ExecutionTargetDTO resumed = target("segment-2");
        UserToolConfirmationEventRequestVO confirmation = confirmation();
        when(fixture.controls.acceptToolConfirmation("session", "call-1", ToolConfirmationResult.DENY, "denied"))
                .thenReturn(new AcceptedControlStreamDTO(stream, new AcceptedControlDTO(null, resumed)));
        when(fixture.dispatcher.stageConfirmation(eq(resumed), eq("call-1"), any()))
                .thenReturn(Optional.of(confirming));

        assertThat(fixture.service.submit(
                        "session", new SubmitSessionEventRequestVO(confirmation), Locale.US, CREDENTIALS))
                .isSameAs(stream);

        ArgumentCaptor<RuntimeEventOutput> output = ArgumentCaptor.forClass(RuntimeEventOutput.class);
        verify(fixture.dispatcher).stageConfirmation(eq(resumed), eq("call-1"), output.capture());
        assertThat(output.getValue()).isInstanceOf(RuntimeResultBackedEventOutput.class);
        verify(fixture.dispatcher).dispatchConfirmation(confirming, "call-1");
    }

    @Test
    void shouldRejectIncompleteCredentialsBeforeAcceptingAnyEvent() {
        Fixture fixture = fixture();
        UserInterruptEventRequestVO interrupt = new UserInterruptEventRequestVO();
        interrupt.setTargetEventId("root");

        assertThatThrownBy(() -> fixture.service.submit(
                        "session", new SubmitSessionEventRequestVO(interrupt), Locale.US, MateCredentials.empty()))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.INVALID_EVENT_REQUEST));
        verify(fixture.controls, never()).acceptInterrupt(any(), any());
    }

    private static Fixture fixture() {
        RuntimeV2MessageEventService messages = mock(RuntimeV2MessageEventService.class);
        RuntimeV2ControlEventService controls = mock(RuntimeV2ControlEventService.class);
        RuntimeSessionControlDispatcher dispatcher = mock(RuntimeSessionControlDispatcher.class);
        RuntimeResultPollingService results = mock(RuntimeResultPollingService.class);
        return new Fixture(
                messages, controls, dispatcher, new RuntimeV2EventService(messages, controls, dispatcher, results));
    }

    private static UserToolConfirmationEventRequestVO confirmation() {
        UserToolConfirmationEventRequestVO request = new UserToolConfirmationEventRequestVO();
        request.setToolCallId("call-1");
        request.setResult("deny");
        request.setDenyMessage("denied");
        return request;
    }

    private static ExecutionTargetDTO target(String segmentId) {
        return new ExecutionTargetDTO("session", "execution", "root", segmentId);
    }

    private record Fixture(
            RuntimeV2MessageEventService messages,
            RuntimeV2ControlEventService controls,
            RuntimeSessionControlDispatcher dispatcher,
            RuntimeV2EventService service) {}
}
