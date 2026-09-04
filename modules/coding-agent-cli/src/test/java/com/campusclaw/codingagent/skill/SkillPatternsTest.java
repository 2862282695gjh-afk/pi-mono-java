/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.skill;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 验证加载与命令发现共享唯一的 Skill 名称约束。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
class SkillPatternsTest {

    @ParameterizedTest
    @ValueSource(strings = {"a", "pdf", "k8s-ops", "a1-b2-c3"})
    void acceptsCompliantNames(String name) {
        assertThat(SkillPatterns.isValidName(name)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {"-", "--", "-pdf", "pdf-", "pdf--tools", "PDF", "pdf tools", "pdf_tools", "pdf.tools", "工具"})
    void rejectsNonCompliantNames(String name) {
        assertThat(SkillPatterns.isValidName(name)).isFalse();
    }

    @Test
    void rejectsNullAndOverlongNames() {
        assertThat(SkillPatterns.isValidName(null)).isFalse();
        assertThat(SkillPatterns.isValidName("a".repeat(65))).isFalse();
        assertThat(SkillPatterns.isValidName("a".repeat(SkillPatterns.MAX_NAME_LENGTH)))
                .isTrue();
    }
}
