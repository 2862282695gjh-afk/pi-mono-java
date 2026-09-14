/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.runtimeapi.dto.AcceptedControlStreamDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionControlDispatcher;
import com.campusclaw.codingagent.runtimeapi.session.PlanConfirmationResult;
import com.campusclaw.codingagent.runtimeapi.session.ToolConfirmationResult;
import com.campusclaw.codingagent.runtimeapi.vo.SessionUserEventRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.SubmitSessionEventRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.UserInterruptEventRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.UserMessageEventRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.UserPlanConfirmationEventRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.UserToolConfirmationEventRequestVO;

import org.springframework.stereotype.Service;

/**
 * 分派 Session Events 三类用户事件，并在控制事务提交后触发本机快路径。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class RuntimeEventService {
    private final RuntimeMessageEventService messages;

    private final RuntimeControlEventService controls;

    private final RuntimeSessionControlDispatcher dispatcher;

    private final RuntimeResultPollingService results;

    public RuntimeEventService(
            RuntimeMessageEventService messages,
            RuntimeControlEventService controls,
            RuntimeSessionControlDispatcher dispatcher,
            RuntimeResultPollingService results) {
        this.messages = messages;
        this.controls = controls;
        this.dispatcher = dispatcher;
        this.results = results;
    }

    public RuntimeEventStream submit(
            String sessionId, SubmitSessionEventRequestVO request, Locale locale, MateCredentials credentials) {
        requireCredentials(credentials);
        SessionUserEventRequestVO event =
                Objects.requireNonNull(request, "request").getEvent();
        return switch (event) {
            case UserMessageEventRequestVO message -> messages.submit(sessionId, message, locale, credentials);
            case UserInterruptEventRequestVO interrupt -> submitInterrupt(sessionId, interrupt);
            case UserToolConfirmationEventRequestVO confirmation -> submitConfirmation(sessionId, confirmation);
            case UserPlanConfirmationEventRequestVO confirmation -> submitPlanConfirmation(sessionId, confirmation);
        };
    }

    private RuntimeEventStream submitInterrupt(String sessionId, UserInterruptEventRequestVO request) {
        AcceptedControlStreamDTO accepted = controls.acceptInterrupt(sessionId, request.getTargetEventId());
        dispatcher.dispatchStop(accepted.acceptance().target());
        return accepted.stream();
    }

    private RuntimeEventStream submitConfirmation(String sessionId, UserToolConfirmationEventRequestVO request) {
        AcceptedControlStreamDTO accepted = controls.acceptToolConfirmation(
                sessionId, request.getToolCallId(), confirmationResult(request.getResult()), request.getDenyMessage());
        RuntimeEventOutput output = new RuntimeResultBackedEventOutput(
                accepted.stream(), accepted.acceptance().target(), results);
        Optional<ExecutionTargetDTO> confirming =
                dispatcher.stageConfirmation(accepted.acceptance().target(), request.getToolCallId(), output);
        confirming.ifPresent(target -> dispatcher.dispatchConfirmation(target, request.getToolCallId()));
        return accepted.stream();
    }

    private RuntimeEventStream submitPlanConfirmation(String sessionId, UserPlanConfirmationEventRequestVO request) {
        AcceptedControlStreamDTO accepted = controls.acceptPlanConfirmation(
                sessionId,
                request.getTargetEventId(),
                planConfirmationResult(request.getResult()),
                request.getFeedback());
        RuntimeEventOutput output = new RuntimeResultBackedEventOutput(
                accepted.stream(), accepted.acceptance().target(), results);
        Optional<ExecutionTargetDTO> confirming =
                dispatcher.stagePlanConfirmation(accepted.acceptance().target(), request.getTargetEventId(), output);
        confirming.ifPresent(target -> dispatcher.dispatchPlanConfirmation(target, request.getTargetEventId()));
        return accepted.stream();
    }

    private static ToolConfirmationResult confirmationResult(String value) {
        return switch (value) {
            case "allow" -> ToolConfirmationResult.ALLOW;
            case "deny" -> ToolConfirmationResult.DENY;
            default -> throw new RuntimeApiException(RuntimeErrorCode.INVALID_EVENT_REQUEST);
        };
    }

    private static PlanConfirmationResult planConfirmationResult(String value) {
        return switch (value) {
            case "approve" -> PlanConfirmationResult.APPROVE;
            case "revise" -> PlanConfirmationResult.REVISE;
            default -> throw new RuntimeApiException(RuntimeErrorCode.INVALID_EVENT_REQUEST);
        };
    }

    private static void requireCredentials(MateCredentials credentials) {
        if (credentials == null || !credentials.isComplete()) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_EVENT_REQUEST);
        }
    }
}
