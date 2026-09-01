/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.web;

import com.huawei.hicampus.claw.codingagent.common.identifier.ResourceIdentifierPatterns;
import com.huawei.hicampus.claw.codingagent.runtimeapi.RuntimeApiConstants;
import com.huawei.hicampus.claw.codingagent.runtimeapi.result.ResultBeanAdapter;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeSessionConfigurationService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeSessionView;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.ChangeModelRequestVO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.ChangeThinkingRequestVO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.GetSessionResponseVO;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Session 模型列表、模型切换与深度思考开关接口。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@RestController
@RequestMapping(RuntimeApiConstants.BASE_PATH + "/sessions/{sessionId}")
public class RuntimeSessionConfigurationController {
    private final RuntimeSessionConfigurationService service;

    private final ResultBeanAdapter resultBeanAdapter;

    public RuntimeSessionConfigurationController(
            RuntimeSessionConfigurationService service, ResultBeanAdapter resultBeanAdapter) {
        this.service = service;
        this.resultBeanAdapter = resultBeanAdapter;
    }

    @GetMapping("/models")
    public ResponseEntity<Object> listModels(
            @PathVariable("sessionId") @NotBlank @Pattern(regexp = ResourceIdentifierPatterns.SESSION_ID_REGEX)
                    String sessionId,
            HttpServletRequest request) {
        Object result = service.listModels(sessionId);
        return success(result, request);
    }

    @PutMapping("/model")
    public ResponseEntity<Object> changeModel(
            @PathVariable("sessionId") @NotBlank @Pattern(regexp = ResourceIdentifierPatterns.SESSION_ID_REGEX)
                    String sessionId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ChangeModelRequestVO body,
            HttpServletRequest request) {
        var view = service.changeModel(sessionId, ifMatch, body);
        return sessionResponse(view, request);
    }

    @PutMapping("/thinking")
    public ResponseEntity<Object> changeThinking(
            @PathVariable("sessionId") @NotBlank @Pattern(regexp = ResourceIdentifierPatterns.SESSION_ID_REGEX)
                    String sessionId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ChangeThinkingRequestVO body,
            HttpServletRequest request) {
        var view = service.changeThinking(sessionId, ifMatch, body);
        return sessionResponse(view, request);
    }

    private ResponseEntity<Object> success(Object result, HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_LANGUAGE, RuntimeRequestContext.language(request))
                .body(resultBeanAdapter.normal(result));
    }

    private ResponseEntity<Object> sessionResponse(
            RuntimeSessionView<GetSessionResponseVO> view, HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(view.etag())
                .header(HttpHeaders.CONTENT_LANGUAGE, RuntimeRequestContext.language(request))
                .body(resultBeanAdapter.normal(view.resource()));
    }
}
