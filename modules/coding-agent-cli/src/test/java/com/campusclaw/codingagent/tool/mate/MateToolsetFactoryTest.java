/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import static com.campusclaw.codingagent.tool.mate.MateToolTestSupport.QUERY_ID;
import static com.campusclaw.codingagent.tool.mate.MateToolTestSupport.SKILL_ID;
import static com.campusclaw.codingagent.tool.mate.MateToolTestSupport.binding;
import static com.campusclaw.codingagent.tool.mate.MateToolTestSupport.meta;
import static com.campusclaw.codingagent.tool.mate.MateToolTestSupport.requiredValueSchema;
import static com.campusclaw.codingagent.tool.mate.MateToolTestSupport.runtime;
import static com.campusclaw.codingagent.tool.mate.MateToolTestSupport.skill;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.campusclaw.codingagent.common.client.mate.MateCredentials;

import org.junit.jupiter.api.Test;

/**
 * {@link MateToolsetFactory} 的 Session 状态与工具实例隔离测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/21]
 * @since [br_eCampusCore 26.0.0]
 */
class MateToolsetFactoryTest {

    @Test
    void shouldKeepMetadataCacheAndCredentialsPrivateToEverySession() throws Exception {
        MockMateToolClient client = new MockMateToolClient();
        client.addTool(meta(QUERY_ID, "Query", requiredValueSchema()));
        client.bindSkill(SKILL_ID, List.of(QUERY_ID));
        var prepared = runtime(List.of(), List.of(skill(List.of(binding(QUERY_ID, "Query", "查询", "allow")))));
        MateToolsetFactory factory = new MateToolsetFactory(client);
        MateCredentials firstCredentials = MateCredentials.appKey("caller-1", "key-1", "access-1");
        MateCredentials secondCredentials = MateCredentials.jwt("caller-2", "token-2", "access-2");
        MateToolSessionState first = factory.createSession(prepared, firstCredentials);
        MateToolSessionState second = factory.createSession(prepared, secondCredentials);

        first.createCallTool().execute("call-1", Map.of("tool", "Query", "args", Map.of("value", "one")), null, null);
        first.createCallTool().execute("call-2", Map.of("tool", "Query", "args", Map.of("value", "two")), null, null);
        assertThat(client.lastCallCredentials()).isSameAs(firstCredentials);
        second.createCallTool()
                .execute("call-3", Map.of("tool", "Query", "args", Map.of("value", "three")), null, null);

        assertThat(first).isNotSameAs(second);
        assertThat(first.createCallTool()).isNotSameAs(first.createCallTool());
        assertThat(first.createSearchTools()).isNotSameAs(first.createSearchTools());
        assertThat(client.skillListCalls()).isEqualTo(2);
        assertThat(client.executeCalls()).isEqualTo(3);
        assertThat(client.lastCallCredentials()).isSameAs(secondCredentials);
    }
}
