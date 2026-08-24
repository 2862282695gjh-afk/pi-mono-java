/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.ai.provider;

import com.campusclaw.ai.stream.AssistantMessageEventStream;
import com.campusclaw.ai.types.Context;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.SimpleStreamOptions;

import jakarta.annotation.Nullable;

/**
 * 按稳定 Provider 身份路由的模型调用接口。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/25]
 * @since [br_eCampusCore 26.0.0]
 */
public interface AiProvider {
    ProviderId id();

    ProviderAuth auth();

    AssistantMessageEventStream streamSimple(Model model, Context context, @Nullable SimpleStreamOptions options);
}
