/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;

import com.huawei.hicampus.claw.ai.provider.AiProvider;
import com.huawei.hicampus.claw.ai.provider.ApiProvider;
import com.huawei.hicampus.claw.ai.provider.ApiProviderRegistry;
import com.huawei.hicampus.claw.ai.provider.mate.CampusMateProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.env.MapPropertySource;

class CampusMateAiWiringTest {
    @Test
    void startsWithOnlyCampusMateAndAnEmptyApiRegistry() {
        try (var context = new AnnotationConfigApplicationContext()) {
            var conversionService = new DefaultConversionService();
            conversionService.addConverter(String.class, Duration.class, Duration::parse);
            context.getBeanFactory().setConversionService(conversionService);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties()));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.scan("com.huawei.hicampus.claw.ai");
            context.refresh();

            Map<String, AiProvider> aiProviders = context.getBeansOfType(AiProvider.class);
            assertEquals(1, aiProviders.size());
            assertSame(
                    context.getBean(CampusMateProvider.class),
                    aiProviders.values().iterator().next());
            assertTrue(context.getBeansOfType(ApiProvider.class).isEmpty());
            assertTrue(context.getBean(ApiProviderRegistry.class).getProviders().isEmpty());
            assertSame(
                    context.getBean(ApiProviderRegistry.class),
                    context.getBean(CampusClawAiService.class).getProviderRegistry());
            assertTrue(java.util.Arrays.stream(context.getBeanDefinitionNames())
                    .noneMatch(CampusMateAiWiringTest::isRemovedCatalogBean));
        }
    }

    private static boolean isRemovedCatalogBean(String beanName) {
        return beanName.equals("modelRegistry")
                || beanName.equals("modelCatalogService")
                || beanName.equals("catalogRuntimeModelManager")
                || beanName.equals("envApiKeyResolver")
                || beanName.equals("envProviderConfigResolver");
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
