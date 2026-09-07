/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;

import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * 验证创建和查询资源复用只读 Usage 投影，且不泄漏可变 DTO 或内部回执。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeSessionResponseAssemblerTest {
    private final RuntimeSessionResponseAssembler assembler =
            new RuntimeSessionResponseAssembler(new SessionEtagFactory());

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void shouldCreateCompleteZeroUsageAndKeepNullNameWithoutInternalFields() {
        var session = new RuntimeSessionDTO();
        session.setId("session");
        session.setResourceVersion(1L);
        var view = assembler.createView(session);
        JsonNode resource = json.valueToTree(view.resource());
        assertThat(resource.properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "sessionId",
                        "agentId",
                        "displayName",
                        "modelId",
                        "state",
                        "thinking",
                        "lifetimeUsage",
                        "createdAt");
        assertThat(resource.get("displayName").isNull()).isTrue();
        assertThat(resource.get("lifetimeUsage").toString())
                .isEqualTo("{\"input\":0,\"output\":0,\"cacheRead\":0,\"cacheWrite\":0,\"totalTokens\":0,"
                        + "\"cost\":{\"input\":0,\"output\":0,\"cacheRead\":0,\"cacheWrite\":0,\"total\":0}}");
        assertThat(view.etag()).isEqualTo(new SessionEtagFactory().create("session", 1L));
    }

    @Test
    void shouldCopyAllUsagePartsFromSameSnapshotAndDetachResponseFromDtoMutation() {
        var session = new RuntimeSessionDTO();
        session.setId("session");
        session.setResourceVersion(9L);
        var usage = session.getLifetimeUsage();
        usage.setInput(4_294_967_294L);
        usage.setOutput(5L);
        usage.setCacheRead(3L);
        usage.setCacheWrite(7L);
        usage.setTotalTokens(101L);
        usage.setCostInput(new BigDecimal("0.03"));
        usage.setCostOutput(new BigDecimal("0.007"));
        usage.setCostCacheRead(new BigDecimal("0.00002"));
        usage.setCostCacheWrite(new BigDecimal("0.000003"));
        usage.setCostTotal(new BigDecimal("0.12345678"));
        var view = assembler.getView(session);
        usage.setInput(0L);
        usage.setCostInput(BigDecimal.ZERO);
        session.setResourceVersion(10L);
        assertThat(json.valueToTree(view.resource()).get("lifetimeUsage").toString())
                .isEqualTo(
                        "{\"input\":4294967294,\"output\":5,\"cacheRead\":3,\"cacheWrite\":7,\"totalTokens\":101,"
                                + "\"cost\":{\"input\":0.03,\"output\":0.007,\"cacheRead\":0.00002,\"cacheWrite\":0.000003,\"total\":0.12345678}}");
        assertThat(view.etag()).isEqualTo(new SessionEtagFactory().create("session", 9L));
    }
}
