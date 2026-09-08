/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.web;

import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeSseDispatcher;
import com.huawei.hicampus.claw.codingagent.runtimeapi.result.ResultBeanAdapter;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.CommandExecutionService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.skill.SkillCommandExecutionService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.BuiltinCommandRequestVO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.CommandRequestVO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SkillCommandRequestVO;
import com.huawei.hicampus.claw.common.constant.ClawConstants;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 共享命令入口：Builtin 完成后返回业务 JSON，Skill 返回普通消息 SSE。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@RestController
@RequestMapping(ClawConstants.RuntimeApi.BASE_PATH + "/sessions/{sessionId}/command")
public class RuntimeCommandController {
    private final CommandExecutionService builtins;

    private final SkillCommandExecutionService skills;

    private final ResultBeanAdapter resultBeanAdapter;

    private final RuntimeSseDispatcher sseDispatcher;

    public RuntimeCommandController(
            CommandExecutionService builtins,
            SkillCommandExecutionService skills,
            ResultBeanAdapter resultBeanAdapter,
            RuntimeSseDispatcher sseDispatcher) {
        this.builtins = builtins;
        this.skills = skills;
        this.resultBeanAdapter = resultBeanAdapter;
        this.sseDispatcher = sseDispatcher;
    }

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Object execute(
            @PathVariable("sessionId") @NotBlank @Pattern(regexp = ClawConstants.Session.ID_REGEX) String sessionId,
            @Valid @RequestBody CommandRequestVO body,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (request.getHeader(HttpHeaders.IF_MATCH) != null || request.getHeader("Idempotency-Key") != null) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
        }
        if (body instanceof SkillCommandRequestVO skill) {
            var emitter = executeSkill(sessionId, skill, request);
            successHeaders(request, response, MediaType.TEXT_EVENT_STREAM_VALUE);
            return emitter;
        }
        var view = builtins.executeBuiltinAndAwait(
                sessionId,
                (BuiltinCommandRequestVO) body,
                RuntimeRequestContext.locale(request),
                RuntimeRequestContext.mateCredentials(request));
        Object result = resultBeanAdapter.normal(view.resource());
        successHeaders(request, response, MediaType.APPLICATION_JSON_VALUE);
        if (view.etag() != null) {
            response.setHeader(HttpHeaders.ETAG, view.etag());
        }
        return result;
    }

    private SseEmitter executeSkill(String sessionId, SkillCommandRequestVO body, HttpServletRequest request) {
        var events = skills.executeRequest(
                sessionId, body, RuntimeRequestContext.locale(request), RuntimeRequestContext.mateCredentials(request));
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(events::detach);
        emitter.onTimeout(events::detach);
        emitter.onError(error -> events.detach());
        events.attach(sseDispatcher, new RuntimeSseEmitterSubscriber(emitter));
        return emitter;
    }

    private static void successHeaders(HttpServletRequest request, HttpServletResponse response, String contentType) {
        response.setContentType(contentType);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.CONTENT_LANGUAGE, RuntimeRequestContext.language(request));
    }
}
