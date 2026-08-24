/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.Cost;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Usage;

/**
 * 累加 Session 生命周期 Token 与 USD 费用明细。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public final class RuntimeUsageAccumulator {
    private RuntimeUsageAccumulator() {}

    public static Usage add(Usage current, Usage increment) {
        Usage left = current == null ? Usage.empty() : current;
        Usage right = increment == null ? Usage.empty() : increment;
        return new Usage(
                left.input() + right.input(),
                left.output() + right.output(),
                left.cacheRead() + right.cacheRead(),
                left.cacheWrite() + right.cacheWrite(),
                left.totalTokens() + right.totalTokens(),
                addCost(left.cost(), right.cost()));
    }

    private static Cost addCost(Cost current, Cost increment) {
        Cost left = current == null ? Cost.empty() : current;
        Cost right = increment == null ? Cost.empty() : increment;
        return new Cost(
                left.input() + right.input(),
                left.output() + right.output(),
                left.cacheRead() + right.cacheRead(),
                left.cacheWrite() + right.cacheWrite(),
                left.total() + right.total());
    }
}
