/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.huawei.hicampus.claw.ai.env.ProviderConfigResolver;
import com.huawei.hicampus.claw.ai.model.ModelRegistry;
import com.huawei.hicampus.claw.ai.types.Model;

import org.springframework.stereotype.Service;

/**
 * 提供按当前服务端凭据过滤后的可用模型目录。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class ModelCatalogService {

    private static final Comparator<Model> MODEL_ORDER =
            Comparator.comparing((Model model) -> model.provider().value()).thenComparing(Model::id);

    private final ModelRegistry modelRegistry;

    private final ProviderConfigResolver providerConfigResolver;

    public ModelCatalogService(ModelRegistry modelRegistry, ProviderConfigResolver providerConfigResolver) {
        this.modelRegistry = modelRegistry;
        this.providerConfigResolver = providerConfigResolver;
    }

    /**
     * 返回当前凭据能够实际调用的模型。
     *
     * @return 按提供商和模型标识稳定排序的模型列表
     */
    public List<Model> getAvailableModels() {
        List<Model> available = new ArrayList<>();
        for (Model model : modelRegistry.getAllModels()) {
            if (hasCredentials(model)) {
                available.add(model);
            }
        }
        available.sort(MODEL_ORDER);
        return List.copyOf(available);
    }

    /**
     * 判断指定模型是否能解析出可用凭据。
     *
     * @param model 待检查模型
     * @return 存在可用凭据时返回 {@code true}
     */
    public boolean hasCredentials(Model model) {
        if (model == null) {
            return false;
        }
        if (model.apiKey() != null && !model.apiKey().isBlank()) {
            return true;
        }
        try {
            var config = providerConfigResolver.resolve(model.provider(), model);
            return config != null && config.apiKey() != null && !config.apiKey().isBlank();
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
