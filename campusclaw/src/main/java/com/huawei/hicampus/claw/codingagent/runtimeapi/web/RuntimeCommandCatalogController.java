/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.web;

import com.huawei.hicampus.claw.codingagent.runtimeapi.result.ResultBeanAdapter;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.CommandCatalogService;
import com.huawei.hicampus.claw.common.constant.ClawConstants;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 发布共享命令清单的只读 HTTP 入口，不执行 Builtin 或 Skill。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
@RestController
@RequestMapping(ClawConstants.RuntimeApi.BASE_PATH)
public class RuntimeCommandCatalogController {
    private final CommandCatalogService service;

    private final ResultBeanAdapter resultBeanAdapter;

    public RuntimeCommandCatalogController(CommandCatalogService service, ResultBeanAdapter resultBeanAdapter) {
        this.service = service;
        this.resultBeanAdapter = resultBeanAdapter;
    }

    @GetMapping("/sessions/{sessionId}/commands")
    public ResponseEntity<Object> list(
            @PathVariable("sessionId") @NotBlank @Pattern(regexp = ClawConstants.Session.ID_REGEX) String sessionId,
            HttpServletRequest request) {
        var response = service.list(sessionId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_LANGUAGE, RuntimeRequestContext.language(request))
                .body(resultBeanAdapter.normal(response));
    }
}
