/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.web;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.identifier.ResourceIdentifierPatterns;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.RuntimeApiConstants;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.result.ResultBeanAdapter;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session.RuntimeSessionControlService;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.ControlMessageAcceptedResponseVO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.ControlMessageRequestVO;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Session Steer、FollowUp 与 Abort 接口。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@RestController
@RequestMapping(RuntimeApiConstants.BASE_PATH + "/sessions/{session_id}")
public class RuntimeSessionControlController {
    private final RuntimeSessionControlService service;

    private final ResultBeanAdapter resultBeanAdapter;

    public RuntimeSessionControlController(RuntimeSessionControlService service, ResultBeanAdapter resultBeanAdapter) {
        this.service = service;
        this.resultBeanAdapter = resultBeanAdapter;
    }

    @PostMapping("/steers")
    public ResponseEntity<Object> steer(
            @PathVariable("session_id") @NotBlank @Pattern(regexp = ResourceIdentifierPatterns.SESSION_ID_REGEX)
                    String sessionId,
            @Valid @RequestBody ControlMessageRequestVO body,
            HttpServletRequest request) {
        var result = service.steer(sessionId, body);
        return accepted(result, request);
    }

    @PostMapping("/follow-ups")
    public ResponseEntity<Object> followUp(
            @PathVariable("session_id") @NotBlank @Pattern(regexp = ResourceIdentifierPatterns.SESSION_ID_REGEX)
                    String sessionId,
            @Valid @RequestBody ControlMessageRequestVO body,
            HttpServletRequest request) {
        var result = service.followUp(sessionId, body);
        return accepted(result, request);
    }

    @PostMapping("/abort")
    public ResponseEntity<Void> abort(
            @PathVariable("session_id") @NotBlank @Pattern(regexp = ResourceIdentifierPatterns.SESSION_ID_REGEX)
                    String sessionId) {
        service.abort(sessionId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private ResponseEntity<Object> accepted(ControlMessageAcceptedResponseVO result, HttpServletRequest request) {
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_LANGUAGE, RuntimeRequestContext.language(request))
                .body(resultBeanAdapter.normal(result));
    }
}
