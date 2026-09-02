/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.util.ClassUtils;

/**
 * 校验 Spring Bean 的多构造器注入入口符合仓库规范。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/25]
 * @since [br_eCampusCore 26.0.0]
 */
class SpringConstructorInjectionConventionTest {
    private static final String BASE_PACKAGE = "com.huawei.hicampus.claw";

    @Test
    void springBeansWithMultipleConstructorsDeclareExactlyOneAutowiredConstructor() {
        List<String> violations = findViolations();

        assertTrue(
                violations.isEmpty(),
                () -> "Spring beans with invalid constructor injection declarations: " + violations);
    }

    private static List<String> findViolations() {
        var scanner = new ClassPathScanningCandidateComponentProvider(true);
        return scanner.findCandidateComponents(BASE_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .filter(Objects::nonNull)
                .map(SpringConstructorInjectionConventionTest::resolveClass)
                .filter(type -> type.getDeclaredConstructors().length > 1)
                .filter(type -> autowiredConstructorCount(type) != 1L)
                .map(SpringConstructorInjectionConventionTest::describeViolation)
                .sorted()
                .toList();
    }

    private static Class<?> resolveClass(String className) {
        return ClassUtils.resolveClassName(className, ClassUtils.getDefaultClassLoader());
    }

    private static long autowiredConstructorCount(Class<?> type) {
        return Arrays.stream(type.getDeclaredConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .count();
    }

    private static String describeViolation(Class<?> type) {
        return type.getName() + " (constructors=" + type.getDeclaredConstructors().length + ", autowired="
                + autowiredConstructorCount(type) + ")";
    }
}
