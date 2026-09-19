/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.ai.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import com.campusclaw.ai.types.Api;
import com.campusclaw.ai.types.Cost;
import com.campusclaw.ai.types.InputModality;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.ModelCost;
import com.campusclaw.ai.types.Provider;
import com.campusclaw.ai.types.Usage;

import org.junit.jupiter.api.Test;

class ModelCostCalculatorTest {
    @Test
    void calculatesCostFromTokenUsage() {
        Usage usage = new Usage(2_000_000, 3_000_000, 4_000_000, 5_000_000, 14_000_000, Cost.empty());

        Cost cost = ModelCostCalculator.calculate(model(new ModelCost(1.0, 2.0, 0.5, 0.25)), usage);

        assertEquals(new Cost(2.0, 6.0, 2.0, 1.25, 11.25), cost);
    }

    @Test
    void returnsEmptyCostWhenModelHasNoPricing() {
        assertEquals(Cost.empty(), ModelCostCalculator.calculate(model(null), Usage.empty()));
    }

    private static Model model(ModelCost cost) {
        return new Model(
                "model-a",
                "Model A",
                Api.OPENAI_COMPLETIONS,
                Provider.CAMPUSMATE,
                null,
                false,
                List.of(InputModality.TEXT),
                cost,
                128_000,
                8_192,
                null,
                null,
                null);
    }
}
