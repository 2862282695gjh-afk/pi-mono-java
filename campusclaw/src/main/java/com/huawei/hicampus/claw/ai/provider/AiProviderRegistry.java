/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.ai.provider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 按 ProviderId 管理通用模型 Provider，避免按 API 协议互相覆盖。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/25]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class AiProviderRegistry {
    private final Map<ProviderId, AiProvider> providers;

    @Autowired
    public AiProviderRegistry(@Autowired(required = false) List<AiProvider> providers) {
        List<AiProvider> values = providers == null ? List.of() : providers;
        this.providers = values.stream().collect(Collectors.toUnmodifiableMap(AiProvider::id, Function.identity()));
    }

    public Optional<AiProvider> getProvider(ProviderId providerId) {
        return Optional.ofNullable(providers.get(providerId));
    }
}
