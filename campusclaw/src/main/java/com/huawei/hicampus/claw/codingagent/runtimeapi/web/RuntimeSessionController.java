/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.web;

import java.net.URI;

import com.huawei.hicampus.claw.codingagent.common.identifier.ResourceIdentifierPatterns;
import com.huawei.hicampus.claw.codingagent.runtimeapi.RuntimeApiConstants;
import com.huawei.hicampus.claw.codingagent.runtimeapi.result.ResultBeanAdapter;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeSessionService;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Runtime Session 创建、查询与删除接口。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@RestController
@RequestMapping(RuntimeApiConstants.BASE_PATH)
public class RuntimeSessionController {
    private final RuntimeSessionService service;

    private final ResultBeanAdapter resultBeanAdapter;

    public RuntimeSessionController(RuntimeSessionService service, ResultBeanAdapter resultBeanAdapter) {
        this.service = service;
        this.resultBeanAdapter = resultBeanAdapter;
    }

    @PostMapping("/agents/{agentId}/sessions")
    public ResponseEntity<Object> create(
            @PathVariable("agentId") @NotBlank @Pattern(regexp = ResourceIdentifierPatterns.AGENT_ID_REGEX)
                    String agentId,
            HttpServletRequest request) {
        var view = service.create(agentId);
        URI location = URI.create(
                RuntimeApiConstants.BASE_PATH + "/sessions/" + view.resource().getSessionId());
        return ResponseEntity.created(location)
                .header(HttpHeaders.CONTENT_LANGUAGE, RuntimeRequestContext.language(request))
                .body(resultBeanAdapter.normal(view.resource()));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<Object> get(
            @PathVariable("sessionId") @NotBlank @Pattern(regexp = ResourceIdentifierPatterns.SESSION_ID_REGEX)
                    String sessionId,
            HttpServletRequest request) {
        var view = service.get(sessionId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(view.etag())
                .header(HttpHeaders.CONTENT_LANGUAGE, RuntimeRequestContext.language(request))
                .body(resultBeanAdapter.normal(view.resource()));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> delete(
            @PathVariable("sessionId") @NotBlank @Pattern(regexp = ResourceIdentifierPatterns.SESSION_ID_REGEX)
                    String sessionId) {
        service.delete(sessionId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
