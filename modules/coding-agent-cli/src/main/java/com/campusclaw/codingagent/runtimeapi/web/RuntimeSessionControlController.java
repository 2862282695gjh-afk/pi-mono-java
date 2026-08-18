/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import java.util.regex.Pattern;

import com.campusclaw.codingagent.runtimeapi.RuntimeApiConstants;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.result.ResultBeanAdapter;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionControlService;
import com.campusclaw.codingagent.runtimeapi.vo.ControlMessageAcceptedResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.ControlMessageRequestVO;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Session Steer、FollowUp 与 Abort 接口。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@RestController
@RequestMapping(RuntimeApiConstants.BASE_PATH + "/sessions/{session_id}")
public class RuntimeSessionControlController {
    private static final Pattern SESSION_ID = Pattern.compile(RuntimeApiConstants.SESSION_ID_PATTERN);

    private final RuntimeSessionControlService service;

    private final ResultBeanAdapter resultBeanAdapter;

    public RuntimeSessionControlController(
            RuntimeSessionControlService service, ResultBeanAdapter resultBeanAdapter) {
        this.service = service;
        this.resultBeanAdapter = resultBeanAdapter;
    }

    @PostMapping("/steers")
    public ResponseEntity<Object> steer(
            @PathVariable("session_id") String sessionId,
            @Valid @RequestBody ControlMessageRequestVO body,
            HttpServletRequest request) {
        requireSessionId(sessionId);
        var result = service.steer(sessionId, body);
        return accepted(result, request);
    }

    @PostMapping("/follow-ups")
    public ResponseEntity<Object> followUp(
            @PathVariable("session_id") String sessionId,
            @Valid @RequestBody ControlMessageRequestVO body,
            HttpServletRequest request) {
        requireSessionId(sessionId);
        var result = service.followUp(sessionId, body);
        return accepted(result, request);
    }

    @PostMapping("/abort")
    public ResponseEntity<Void> abort(@PathVariable("session_id") String sessionId) {
        requireSessionId(sessionId);
        service.abort(sessionId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private ResponseEntity<Object> accepted(
            ControlMessageAcceptedResponseVO result, HttpServletRequest request) {
        return ResponseEntity.accepted()
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
