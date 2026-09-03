/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Strict Skill command name validation rules aligned with the Agent Skills
 * specification.
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/02]
 * @since [br_eCampusCore 26.0.0]
 */
class SkillNameValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"a", "pdf", "k8s-ops", "a1-b2-c3"})
    void acceptsCompliantNames(String name) {
        assertThat(SkillNameValidator.isValid(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-pdf", "pdf-", "pdf--tools", "PDF", "pdf tools", "pdf_tools", "pdf.tools", "skill:", "工具"})
    void rejectsNonCompliantNames(String name) {
        assertThat(SkillNameValidator.isValid(name)).isFalse();
    }

    @Test
    void rejectsNullAndOverlongNames() {
        assertThat(SkillNameValidator.isValid(null)).isFalse();
        assertThat(SkillNameValidator.isValid("a".repeat(65))).isFalse();
        assertThat(SkillNameValidator.isValid("a".repeat(64))).isTrue();
    }

    @Test
    void strictPatternRejectsLegacyLooseSamples() {
        assertThat(Stream.of("-lead", "trail-", "double--hyphen").allMatch(SkillNameValidator::isValid))
                .isFalse();
    }
}
