/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * StatusController 单元测试，验证健康检查接口的响应行为。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public class StatusControllerTest {
    private StatusController statusController;

    @BeforeEach
    void setUp() {
        statusController = new StatusController();
    }

    /**
     * 验证健康检查返回 HTTP 200 状态码和固定响应体 Success。
     *
     * <p>前置条件：初始化 StatusController 对象，无输入参数。
     * 执行操作：调用一次 statusController.status()。
     * 预期结果：返回 ResponseEntity，状态码为 200，响应体为 Success。
     */
    @Test
    void statusReturnsOkWithSuccessBody() {
        // 执行健康检查。
        ResponseEntity<String> response = statusController.status();

        // 校验状态码和响应体。
        assertThat(response.getStatusCode()).as("健康检查应返回 HTTP 200").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).as("健康检查响应体应为 Success").isEqualTo("Success");
    }
}
