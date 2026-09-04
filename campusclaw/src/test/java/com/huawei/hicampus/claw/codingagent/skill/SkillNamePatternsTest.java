/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.skill;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 验证兼容加载与严格发现规则共享名称约束。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
class SkillNamePatternsTest {

    @ParameterizedTest
    @ValueSource(strings = {"a", "pdf", "k8s-ops", "a1-b2-c3"})
    void strictAcceptsCompliantNames(String name) {
        assertThat(SkillNamePatterns.isStrictValid(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-pdf", "pdf-", "pdf--tools", "PDF", "pdf tools", "pdf_tools", "pdf.tools", "工具"})
    void strictRejectsNonCompliantNames(String name) {
        assertThat(SkillNamePatterns.isStrictValid(name)).isFalse();
    }

    @Test
    void strictRejectsNullAndOverlongNames() {
        assertThat(SkillNamePatterns.isStrictValid(null)).isFalse();
        assertThat(SkillNamePatterns.isStrictValid("a".repeat(65))).isFalse();
        assertThat(SkillNamePatterns.isStrictValid("a".repeat(SkillNamePatterns.MAX_NAME_LENGTH)))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-pdf", "pdf-", "pdf--tools"})
    void legacyLoadingRuleKeepsAcceptingExistingNames(String name) {
        assertThat(SkillNamePatterns.isLegacyValid(name)).isTrue();
    }

    @Test
    void legacyRuleStillRejectsWhitespaceAndCase() {
        assertThat(SkillNamePatterns.isLegacyValid("PDF")).isFalse();
        assertThat(SkillNamePatterns.isLegacyValid("pdf tools")).isFalse();
        assertThat(SkillNamePatterns.isLegacyValid(null)).isFalse();
    }
}
