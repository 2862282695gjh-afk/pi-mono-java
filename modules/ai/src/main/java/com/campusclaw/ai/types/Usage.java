/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.ai.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 大模型调用的 Token 用量、费用与上游是否明确报告的来源标志。
 *
 * @param input 输入 Token 数
 * @param output 输出 Token 数
 * @param cacheRead 命中缓存的 Token 数
 * @param cacheWrite 写入缓存的 Token 数
 * @param totalTokens 总 Token 数
 * @param cost 美元费用明细
 * @param known 上游是否明确报告本次用量
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public record Usage(
        @JsonProperty("input") int input,
        @JsonProperty("output") int output,
        @JsonProperty("cacheRead") int cacheRead,
        @JsonProperty("cacheWrite") int cacheWrite,
        @JsonProperty("totalTokens") int totalTokens,
        @JsonProperty("cost") Cost cost,
        @JsonProperty("known") boolean known) {

    public Usage(
            @JsonProperty("input") int input,
            @JsonProperty("output") int output,
            @JsonProperty("cacheRead") int cacheRead,
            @JsonProperty("cacheWrite") int cacheWrite,
            @JsonProperty("totalTokens") int totalTokens,
            @JsonProperty("cost") Cost cost) {
        this(input, output, cacheRead, cacheWrite, totalTokens, cost, true);
    }

    /**
     * 从 JSON 恢复用量及其来源标志，并兼容没有 {@code known} 的历史记录。
     *
     * @param input 输入 Token 数
     * @param output 输出 Token 数
     * @param cacheRead 命中缓存的 Token 数
     * @param cacheWrite 写入缓存的 Token 数
     * @param totalTokens 总 Token 数
     * @param cost 美元费用明细
     * @param known 可选来源标志
     * @return 恢复后的用量
     */
    @JsonCreator
    public static Usage fromJson(
            @JsonProperty("input") int input,
            @JsonProperty("output") int output,
            @JsonProperty("cacheRead") int cacheRead,
            @JsonProperty("cacheWrite") int cacheWrite,
            @JsonProperty("totalTokens") int totalTokens,
            @JsonProperty("cost") Cost cost,
            @JsonProperty("known") Boolean known) {
        boolean reported = known != null
                ? known
                : input != 0 || output != 0 || cacheRead != 0 || cacheWrite != 0 || totalTokens != 0;
        return new Usage(input, output, cacheRead, cacheWrite, totalTokens, cost, reported);
    }

    /**
     * 返回未收到上游用量时使用的零值占位。
     *
     * @return 数值为零且 {@link #known()} 为 {@code false} 的用量
     */
    public static Usage empty() {
        return new Usage(0, 0, 0, 0, 0, Cost.empty(), false);
    }
}
