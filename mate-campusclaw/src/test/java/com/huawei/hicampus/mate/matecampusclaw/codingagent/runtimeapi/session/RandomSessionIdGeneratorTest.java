/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.identifier.ResourceIdentifierPatterns;

import org.junit.jupiter.api.Test;

/**
 * 类型化 Session UUID 生成规则测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/20]
 * @since [br_eCampusCore 26.0.0]
 */
class RandomSessionIdGeneratorTest {
    @Test
    void generatesUniqueTypedUuidWithoutInternalHyphens() {
        RandomSessionIdGenerator generator = new RandomSessionIdGenerator();

        String first = generator.nextId();
        String second = generator.nextId();

        assertThat(first).matches(ResourceIdentifierPatterns.SESSION_ID_REGEX);
        assertThat(second).matches(ResourceIdentifierPatterns.SESSION_ID_REGEX).isNotEqualTo(first);
    }
}
