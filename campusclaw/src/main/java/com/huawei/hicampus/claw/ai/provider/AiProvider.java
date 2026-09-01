/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.ai.provider;

import com.huawei.hicampus.claw.ai.stream.AssistantMessageEventStream;
import com.huawei.hicampus.claw.ai.types.Context;
import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.ai.types.SimpleStreamOptions;

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
