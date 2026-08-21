/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import com.campusclaw.codingagent.common.identifier.ResourceIdentifierPatterns;
import com.campusclaw.codingagent.runtimeapi.RuntimeApiConstants;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventQueryService;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventService;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeSseDispatcher;
import com.campusclaw.codingagent.runtimeapi.result.ResultBeanAdapter;
import com.campusclaw.codingagent.runtimeapi.vo.UserEventRequestVO;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Session Event 提交 SSE 与当前分支历史分页接口。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@RestController
@RequestMapping(RuntimeApiConstants.BASE_PATH + "/sessions/{session_id}/events")
public class RuntimeEventController {
    private final RuntimeEventService service;

    private final RuntimeEventQueryService queryService;

    private final ResultBeanAdapter resultBeanAdapter;

    private final RuntimeSseDispatcher sseDispatcher;

    public RuntimeEventController(
            RuntimeEventService service,
            RuntimeEventQueryService queryService,
            ResultBeanAdapter resultBeanAdapter,
            RuntimeSseDispatcher sseDispatcher) {
        this.service = service;
        this.queryService = queryService;
        this.resultBeanAdapter = resultBeanAdapter;
        this.sseDispatcher = sseDispatcher;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> submit(
            @PathVariable("session_id") @NotBlank @Pattern(regexp = ResourceIdentifierPatterns.SESSION_ID_REGEX)
                    String sessionId,
            @Valid @RequestBody UserEventRequestVO body,
            HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        var events = service.submit(sessionId, body, RuntimeRequestContext.locale(request));
        emitter.onCompletion(events::detach);
        emitter.onTimeout(events::detach);
        emitter.onError(error -> events.detach());
        events.attach(sseDispatcher, new RuntimeSseEmitterSubscriber(emitter));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_LANGUAGE, RuntimeRequestContext.language(request))
                .body(emitter);
    }

    @GetMapping
    public ResponseEntity<Object> list(
            @PathVariable("session_id") @NotBlank @Pattern(regexp = ResourceIdentifierPatterns.SESSION_ID_REGEX)
                    String sessionId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String page,
            HttpServletRequest request) {
        var result = queryService.list(sessionId, limit, page);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_LANGUAGE, RuntimeRequestContext.language(request))
                .body(resultBeanAdapter.normal(result));
    }
}
