/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.ai.model;

import com.huawei.hicampus.claw.ai.types.Cost;
import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.ai.types.Usage;

/**
 * 根据模型价格与 Token 用量计算调用费用。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/19]
 * @since [br_eCampusCore 26.0.0]
 */
public final class ModelCostCalculator {
    private static final double TOKENS_PER_MILLION = 1_000_000.0;

    private ModelCostCalculator() {}

    /**
     * 计算一次模型调用的费用明细。
     *
     * @param model 提供价格的模型
     * @param usage Token 用量
     * @return 费用明细；模型未配置价格时返回空费用
     */
    public static Cost calculate(Model model, Usage usage) {
        if (model.cost() == null) {
            return Cost.empty();
        }
        var price = model.cost();
        double input = usage.input() * price.input() / TOKENS_PER_MILLION;
        double output = usage.output() * price.output() / TOKENS_PER_MILLION;
        double cacheRead = usage.cacheRead() * price.cacheRead() / TOKENS_PER_MILLION;
        double cacheWrite = usage.cacheWrite() * price.cacheWrite() / TOKENS_PER_MILLION;
        return new Cost(input, output, cacheRead, cacheWrite, input + output + cacheRead + cacheWrite);
    }
}
