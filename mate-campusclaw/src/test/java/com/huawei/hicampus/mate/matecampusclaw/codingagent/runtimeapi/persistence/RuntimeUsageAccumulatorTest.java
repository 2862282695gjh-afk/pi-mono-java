/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.Cost;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Usage;

import org.junit.jupiter.api.Test;

/**
 * 验证生命周期 Token 与费用明细的逐项累计。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeUsageAccumulatorTest {
    @Test
    void addsEveryTokenAndUsdCostField() {
        Usage first = new Usage(10, 4, 3, 2, 19, new Cost(0.1, 0.2, 0.03, 0.02, 0.35));
        Usage second = new Usage(7, 5, 1, 4, 17, new Cost(0.07, 0.25, 0.01, 0.04, 0.37));

        Usage total = RuntimeUsageAccumulator.add(first, second);

        assertThat(total).isEqualTo(new Usage(17, 9, 4, 6, 36, new Cost(0.17, 0.45, 0.04, 0.06, 0.72)));
    }
}
