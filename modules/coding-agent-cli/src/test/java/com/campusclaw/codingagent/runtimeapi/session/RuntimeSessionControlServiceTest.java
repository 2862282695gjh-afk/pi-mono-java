/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.campusclaw.agent.Agent;
import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.runtimeapi.auth.CallerAuthContext;
import com.campusclaw.codingagent.runtimeapi.auth.CredentialMode;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventStream;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;
import com.campusclaw.codingagent.runtimeapi.vo.ControlMessageRequestVO;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;

/**
 * Session 控制消息接受与中止收敛的业务测试。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class RuntimeSessionControlServiceTest {
    private static final String SESSION_ID = "session_control";

    private static final String OWNER_ID = "mate-service";

    private RuntimeSessionRepository repository;

    private RuntimeSessionEngineRegistry engines;

    private Agent agent;

    private RuntimeSessionHolder holder;

    private RuntimeActiveExecution execution;

    private RuntimeSessionControlService service;

    @BeforeEach
    void setUp() {
        repository = mock(RuntimeSessionRepository.class);
        engines = mock(RuntimeSessionEngineRegistry.class);
        agent = mock(Agent.class);
        holder = new RuntimeSessionHolder(SESSION_ID, null, agent);
        execution = new RuntimeActiveExecution(new RuntimeEventStream());
        assertThat(holder.begin(execution)).isTrue();
        when(engines.find(SESSION_ID)).thenReturn(Optional.of(holder));
        service = new RuntimeSessionControlService(
                repository,
                engines,
                Validation.buildDefaultValidatorFactory().getValidator(),
                Clock.fixed(Instant.parse("2026-08-18T07:10:00Z"), ZoneOffset.UTC));
    }

    @Test
    void acceptsSteerIntoCurrentExecutionWithoutPersistingIt() throws Exception {
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(session("running")));

        var result = service.steer(SESSION_ID, caller(), request("先只分析异常订单"));

        assertThat(result.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(result.getAcceptedAt()).isEqualTo(OffsetDateTime.parse("2026-08-18T07:10:00Z"));
        verify(agent).steer(any(UserMessage.class));
        verify(repository, never()).appendEntry(any());
    }

    @Test
    void acceptsFollowUpIntoLowerPriorityQueue() throws Exception {
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(session("running")));

        service.followUp(SESSION_ID, caller(), request("完成后再给出摘要"));

        verify(agent).followUp(any(UserMessage.class));
        verify(agent, never()).steer(any());
    }

    @Test
    void rejectsControlMessageWhenSessionIsIdle() throws Exception {
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(session("idle")));

        assertThatThrownBy(() -> service.steer(SESSION_ID, caller(), request("继续")))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.SESSION_NOT_RUNNING));
    }

    @Test
    void rejectsNewMessagesAfterAbortHasClosedAcceptance() throws Exception {
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(session("running")));
        execution.requestAbort();

        assertThatThrownBy(() -> service.followUp(SESSION_ID, caller(), request("继续")))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.SESSION_NOT_RUNNING));
    }

    @Test
    void rejectsBlankSteerWithEndpointSpecificCode() throws Exception {
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(session("running")));

        assertThatThrownBy(() -> service.steer(SESSION_ID, caller(), request("   ")))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.INVALID_STEER_REQUEST));

        verify(agent, never()).steer(any());
    }

    @Test
    void mapsMissingRunningEngineToAcceptanceFailure() throws Exception {
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(session("running")));
        when(engines.find(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.followUp(SESSION_ID, caller(), request("继续")))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.FOLLOW_UP_ACCEPTANCE_FAILED));
    }

    @Test
    void abortClosesQueuesCancelsAgentAndWaitsForCompletion() {
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(session("running")));
        execution.complete(null);

        service.abort(SESSION_ID, caller());

        assertThat(execution.abortRequested()).isTrue();
        verify(agent).clearSteeringQueue();
        verify(agent).clearFollowUpQueue();
        verify(agent).abort();
    }

    @Test
    void abortIsNoOpForIdleSession() {
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(session("idle")));

        service.abort(SESSION_ID, caller());

        verify(agent).clearSteeringQueue();
        verify(agent).clearFollowUpQueue();
        verify(agent, never()).abort();
    }

    @Test
    void usesControlSpecificForbiddenMessage() throws Exception {
        RuntimeSessionDTO session = session("running");
        session.setOwnerId("different-owner");
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.steer(SESSION_ID, caller(), request("继续")))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.FORBIDDEN);
                    assertThat(error.localizedMessage(true)).isEqualTo("当前调用方无权控制该 Session。");
                });
    }

    private static ControlMessageRequestVO request(String message) throws Exception {
        return new ObjectMapper().readValue("{\"message\":\"" + message + "\"}", ControlMessageRequestVO.class);
    }

    private static CallerAuthContext caller() {
        return new CallerAuthContext(OWNER_ID, CredentialMode.JWT);
    }

    private static RuntimeSessionDTO session(String state) {
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setId(SESSION_ID);
        session.setOwnerId(OWNER_ID);
        session.setState(state);
        return session;
    }
}
