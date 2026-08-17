/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.CallMateTool;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link MateToolProperties}: the setter/getter pair for
 * the approval callback must round-trip the same instance. A previous bug had
 * two fields ({@code approvalUi} / {@code approvalUI}) so the value written by
 * the setter was silently dropped by the getter — Spring binds via the setter
 * while {@code MateToolAutoConfiguration} reads the getter, losing the
 * configured approval callback entirely.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
class MateToolPropertiesTest {

    @Test
    void approvalUiSetterAndGetterRoundTripSameInstance() {
        MateToolProperties properties = new MateToolProperties();
        CallMateTool.MateApprovalUI callback = (tool, args, description) -> true;

        properties.setApprovalUi(callback);

        assertThat(properties.getApprovalUi()).isSameAs(callback);
    }

    @Test
    void defaultApprovalUiFailsClosed() {
        MateToolProperties properties = new MateToolProperties();

        CallMateTool.MateApprovalUI approvalUi = properties.getApprovalUi();

        assertThat(approvalUi.ask("any-tool", Map.of(), "desc")).isFalse();
    }
}
