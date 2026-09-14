/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import com.huawei.hicampus.claw.ai.model.ModelRegistry;
import com.huawei.hicampus.claw.ai.provider.AiProvider;
import com.huawei.hicampus.claw.ai.provider.AiProviderRegistry;
import com.huawei.hicampus.claw.ai.provider.ApiProvider;
import com.huawei.hicampus.claw.ai.provider.ApiProviderRegistry;
import com.huawei.hicampus.claw.ai.provider.mate.CampusMateProvider;
import com.huawei.hicampus.claw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.env.MapPropertySource;

class CampusMateAiWiringTest {
    @TempDir
    Path tempDir;

    private String previousHome;

    @AfterEach
    void tearDown() {
        if (previousHome == null) {
            System.clearProperty(ClawConstants.Home.PROPERTY);
        } else {
            System.setProperty(ClawConstants.Home.PROPERTY, previousHome);
        }
    }

    @Test
    void startsWithOnlyCampusMateAndAnEmptyApiRegistry() {
        previousHome = System.getProperty(ClawConstants.Home.PROPERTY);
        System.setProperty(ClawConstants.Home.PROPERTY, tempDir.toString());
        try (var context = new AnnotationConfigApplicationContext()) {
            var conversionService = new DefaultConversionService();
            conversionService.addConverter(String.class, Duration.class, Duration::parse);
            context.getBeanFactory().setConversionService(conversionService);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties()));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(
                    CampusMateProvider.class,
                    AiProviderRegistry.class,
                    ApiProviderRegistry.class,
                    ModelRegistry.class,
                    CampusClawAiService.class);
            context.refresh();

            Map<String, AiProvider> aiProviders = context.getBeansOfType(AiProvider.class);
            assertEquals(1, aiProviders.size());
            assertSame(
                    context.getBean(CampusMateProvider.class),
                    aiProviders.values().iterator().next());
            assertTrue(context.getBeansOfType(ApiProvider.class).isEmpty());
            assertTrue(context.getBean(ApiProviderRegistry.class).getProviders().isEmpty());
            assertTrue(context.getBean(ModelRegistry.class).getAllModels().isEmpty());
        }
    }

    private static Map<String, Object> properties() {
        return Map.of(
                "campusmate.base-url", "https://campusmate.example.com",
                "campusmate.endpoints.model-chat-path", "/mate-service/v1/LLM/chat",
                "campusmate.model.api", "openai-completions",
                "campusmate.model.connect-timeout", "PT1S",
                "campusmate.model.response-timeout", "PT2S");
    }
}
