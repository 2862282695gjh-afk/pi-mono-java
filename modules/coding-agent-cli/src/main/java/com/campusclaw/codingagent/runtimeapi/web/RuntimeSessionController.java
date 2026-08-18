/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import java.net.URI;
import java.util.regex.Pattern;

import com.campusclaw.codingagent.runtimeapi.RuntimeApiConstants;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.result.ResultBeanAdapter;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionService;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Runtime Session 创建、查询与删除接口。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@RestController
@RequestMapping(RuntimeApiConstants.BASE_PATH)
public class RuntimeSessionController {
    private static final Pattern AGENT_ID = Pattern.compile(RuntimeApiConstants.AGENT_ID_PATTERN);

    private static final Pattern SESSION_ID = Pattern.compile(RuntimeApiConstants.SESSION_ID_PATTERN);

    private final RuntimeSessionService service;

    private final ResultBeanAdapter resultBeanAdapter;

    public RuntimeSessionController(RuntimeSessionService service, ResultBeanAdapter resultBeanAdapter) {
        this.service = service;
        this.resultBeanAdapter = resultBeanAdapter;
    }

    @PostMapping("/agents/{agent_id}/sessions")
    public ResponseEntity<Object> create(
            @PathVariable("agent_id") String agentId, HttpServletRequest request) {
        requireIdentifier(agentId, AGENT_ID, RuntimeErrorCode.INVALID_AGENT_ID);
        var view = service.create(agentId);
        URI location = URI.create(RuntimeApiConstants.BASE_PATH + "/sessions/" + view.resource().getSessionId());
        return ResponseEntity.created(location)
                .header(HttpHeaders.CONTENT_LANGUAGE, RuntimeRequestContext.language(request))
                .body(resultBeanAdapter.normal(view.resource()));
    }

    @GetMapping("/sessions/{session_id}")
    public ResponseEntity<Object> get(
            @PathVariable("session_id") String sessionId, HttpServletRequest request) {
        requireIdentifier(sessionId, SESSION_ID, RuntimeErrorCode.INVALID_SESSION_ID);
        var view = service.get(sessionId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(view.etag())
                .header(HttpHeaders.CONTENT_LANGUAGE, RuntimeRequestContext.language(request))
                .body(resultBeanAdapter.normal(view.resource()));
    }

    @DeleteMapping("/sessions/{session_id}")
    public ResponseEntity<Void> delete(@PathVariable("session_id") String sessionId) {
        requireIdentifier(sessionId, SESSION_ID, RuntimeErrorCode.INVALID_SESSION_ID);
        service.delete(sessionId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static void requireIdentifier(String value, Pattern pattern, RuntimeErrorCode errorCode) {
        if (!pattern.matcher(value).matches()) {
            throw new RuntimeApiException(HttpStatus.BAD_REQUEST, errorCode);
        }
    }
}
