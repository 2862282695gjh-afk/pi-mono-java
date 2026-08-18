/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.web;

import java.util.regex.Pattern;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.RuntimeApiConstants;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event.RuntimeEventService;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event.RuntimeSseDispatcher;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.result.ResultBeanAdapter;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.UserEventRequestVO;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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

/**
 * Session Event 提交 SSE 与当前分支历史分页接口。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@RestController
@RequestMapping(RuntimeApiConstants.BASE_PATH + "/sessions/{session_id}/events")
public class RuntimeEventController {
    private static final Pattern SESSION_ID = Pattern.compile(RuntimeApiConstants.SESSION_ID_PATTERN);

    private final RuntimeEventService service;

    private final ResultBeanAdapter resultBeanAdapter;

    private final RuntimeSseDispatcher sseDispatcher;

    public RuntimeEventController(
            RuntimeEventService service,
            ResultBeanAdapter resultBeanAdapter,
            RuntimeSseDispatcher sseDispatcher) {
        this.service = service;
        this.resultBeanAdapter = resultBeanAdapter;
        this.sseDispatcher = sseDispatcher;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> submit(
            @PathVariable("session_id") String sessionId,
            @Valid @RequestBody UserEventRequestVO body,
            HttpServletRequest request) {
        requireSessionId(sessionId);
        SseEmitter emitter = new SseEmitter(0L);
        var events = service.submit(sessionId, body, RuntimeRequestContext.chinese(request));
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
            @PathVariable("session_id") String sessionId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String page,
            HttpServletRequest request) {
        requireSessionId(sessionId);
        var result = service.list(sessionId, limit, page);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_LANGUAGE, RuntimeRequestContext.language(request))
                .body(resultBeanAdapter.normal(result));
    }

    private static void requireSessionId(String sessionId) {
        if (!SESSION_ID.matcher(sessionId).matches()) {
            throw new RuntimeApiException(HttpStatus.BAD_REQUEST, RuntimeErrorCode.INVALID_SESSION_ID);
        }
    }
}
