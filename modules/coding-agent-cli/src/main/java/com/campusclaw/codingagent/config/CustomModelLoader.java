/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.campusclaw.ai.model.ModelRegistry;
import com.campusclaw.ai.types.Api;
import com.campusclaw.ai.types.InputModality;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.ModelCost;
import com.campusclaw.ai.types.Provider;
import com.campusclaw.codingagent.settings.Settings;
import com.campusclaw.codingagent.settings.SettingsManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Spring 就绪后将用户自定义模型注册到 {@link ModelRegistry}。
 *
 * <p>模型来源包括 {@code settings.customModels} 和
 * {@code ~/.campusclaw/agent/models.json}；启动时执行一次，确保模型选择器与
 * {@code -m} 参数使用同一份模型目录。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class CustomModelLoader {

    private static final Logger log = LoggerFactory.getLogger(CustomModelLoader.class);

    private final SettingsManager settingsManager;
    private final ModelRegistry modelRegistry;

    public CustomModelLoader(SettingsManager settingsManager, ModelRegistry modelRegistry) {
        this.settingsManager = settingsManager;
        this.modelRegistry = modelRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerCustomModels() {
        refresh();
    }

    /**
     * 使用当前 {@code settings.customModels} 重新同步注册表中的
     * {@link Provider#CUSTOM} 分组。
     *
     * <p>先移除已经不存在的自定义模型，再注册最新集合，不影响其他供应商的内置模型。
     * 启动时由 {@code @EventListener} 调用一次，写入
     * CLI 配置刷新后也会调用，使后续模型解析读取到新目录。
     */
    public void refresh() {
        Settings settings;
        try {
            settings = settingsManager.load();
        } catch (RuntimeException e) {
            log.warn("Failed to load settings for custom model refresh", e);
            return;
        }

        modelRegistry.unregisterByProvider(Provider.CUSTOM);

        if (settings.customModels() == null || settings.customModels().isEmpty()) {
            log.debug("No custom models configured; registry left without {} entries", Provider.CUSTOM.value());
            return;
        }

        List<Model> toRegister = new ArrayList<>();
        for (Settings.CustomModelConfig cfg : settings.customModels()) {
            try {
                toRegister.add(toModel(cfg));
            } catch (RuntimeException e) {
                log.warn("Skipping invalid custom model {}: {}", cfg.id(), e.getMessage());
            }
        }
        if (!toRegister.isEmpty()) {
            modelRegistry.registerAll(toRegister);
            log.info("Registered {} custom model(s) from settings.json", toRegister.size());
        }
    }

    private static Model toModel(Settings.CustomModelConfig cfg) {
        Api api = Api.fromValue(cfg.api());
        Provider provider = Provider.CUSTOM;
        ModelCost cost = new ModelCost(0, 0, 0, 0);
        List<InputModality> modalities = new ArrayList<>();
        if (cfg.inputModalities() != null) {
            for (String m : cfg.inputModalities()) {
                try {
                    modalities.add(InputModality.valueOf(m.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    // 忽略 settings.json 中未知的模态字符串，后续回退为 TEXT。
                    log.debug("ignoring unknown input modality '{}' from custom model config", m, e);
                }
            }
        }
        if (modalities.isEmpty()) {
            modalities.add(InputModality.TEXT);
        }
        return new Model(
                cfg.id(),
                cfg.name() != null ? cfg.name() : cfg.id(),
                api,
                provider,
                ConfigValueResolver.resolve(cfg.baseUrl()),
                cfg.reasoning() != null && cfg.reasoning(),
                modalities,
                cost,
                cfg.contextWindow() != null ? cfg.contextWindow() : 128000,
                cfg.maxTokens() != null ? cfg.maxTokens() : 8192,
                null,
                cfg.thinkingFormat(),
                ConfigValueResolver.resolve(cfg.apiKey()));
    }
}
