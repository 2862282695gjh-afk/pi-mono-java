/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolMeta;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.identifier.ResourceIdentifierPatterns;

/**
 * 保存单个 Session 的 Mate 工具来源快照和完整名称到标识索引。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public class MateToolSessionCache {

    private final Object stateLock = new Object();

    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile CacheState state = new CacheState(Map.of(), Map.of(), 0, null);

    /**
     * 更新一个发现来源，并保留其他来源的最近成功快照。
     *
     * @param source 发现来源
     * @param tools 该来源的实时结果
     */
    public void updateSource(MateToolSource source, List<MateToolMeta> tools) {
        Map<String, String> sourceSnapshot = toNameIndex(tools);
        synchronized (stateLock) {
            Map<MateToolSource, Map<String, String>> updated = new LinkedHashMap<>(state.sources());
            updated.put(source, sourceSnapshot);
            Map<String, String> complete = mergeSources(updated);
            state = new CacheState(copySources(updated), complete, state.fullRefreshGeneration(), null);
        }
    }

    /**
     * 按名称查询当前完整有效快照。
     *
     * @param toolName 工具名称
     * @return 内部工具标识；未命中时为空
     */
    public String lookupToolId(String toolName) {
        return state.complete().get(toolName);
    }

    /**
     * 缓存未命中时执行一次完整发现；并发 miss 共享同一轮刷新结果。
     *
     * @param toolName 待解析名称
     * @param loader 完整来源加载器
     * @return 内部工具标识；刷新后仍未命中时为空
     */
    public String resolveOrRefresh(String toolName, Supplier<Map<MateToolSource, List<MateToolMeta>>> loader) {
        CacheState observed = state;
        String cached = observed.complete().get(toolName);
        if (cached != null) {
            return cached;
        }
        refreshLock.lock();
        try {
            CacheState current = state;
            cached = current.complete().get(toolName);
            if (cached != null) {
                return cached;
            }
            if (current.fullRefreshGeneration() != observed.fullRefreshGeneration()) {
                throwRefreshFailure(current);
                return null;
            }
            try {
                replaceAllSources(loader.get());
            } catch (RuntimeException exception) {
                recordRefreshFailure(exception);
                throw exception;
            }
            return state.complete().get(toolName);
        } finally {
            refreshLock.unlock();
        }
    }

    private void replaceAllSources(Map<MateToolSource, List<MateToolMeta>> discovered) {
        Map<MateToolSource, Map<String, String>> sources = new LinkedHashMap<>();
        discovered.forEach((source, tools) -> sources.put(source, toNameIndex(tools)));
        Map<String, String> complete = mergeSources(sources);
        synchronized (stateLock) {
            long generation = state.fullRefreshGeneration() + 1;
            state = new CacheState(copySources(sources), complete, generation, null);
        }
    }

    private void recordRefreshFailure(RuntimeException exception) {
        synchronized (stateLock) {
            long generation = state.fullRefreshGeneration() + 1;
            state = new CacheState(state.sources(), state.complete(), generation, exception);
        }
    }

    private static void throwRefreshFailure(CacheState state) {
        if (state.refreshFailure() != null) {
            throw state.refreshFailure();
        }
    }

    private static Map<String, String> toNameIndex(List<MateToolMeta> tools) {
        Map<String, String> index = new LinkedHashMap<>();
        for (MateToolMeta tool : tools == null ? List.<MateToolMeta>of() : tools) {
            if (tool.toolName() == null
                    || tool.toolName().isBlank()
                    || tool.toolId() == null
                    || !ResourceIdentifierPatterns.TOOL_ID_PATTERN
                            .matcher(tool.toolId())
                            .matches()) {
                throw new IllegalStateException("Mate tool metadata is incomplete");
            }
            String previous = index.putIfAbsent(tool.toolName(), tool.toolId());
            if (previous != null && !previous.equals(tool.toolId())) {
                throw new IllegalStateException("Duplicate Mate tool name maps to different ids");
            }
        }
        return Map.copyOf(index);
    }

    private static Map<String, String> mergeSources(Map<MateToolSource, Map<String, String>> sources) {
        Map<String, String> complete = new LinkedHashMap<>();
        for (Map<String, String> source : sources.values()) {
            source.forEach((name, id) -> mergeName(complete, name, id));
        }
        return Map.copyOf(complete);
    }

    private static void mergeName(Map<String, String> complete, String name, String id) {
        String previous = complete.putIfAbsent(name, id);
        if (previous != null && !previous.equals(id)) {
            throw new IllegalStateException("Duplicate Mate tool name maps to different ids");
        }
    }

    private static Map<MateToolSource, Map<String, String>> copySources(
            Map<MateToolSource, Map<String, String>> sources) {
        return Map.copyOf(sources);
    }

    private record CacheState(
            Map<MateToolSource, Map<String, String>> sources,
            Map<String, String> complete,
            long fullRefreshGeneration,
            RuntimeException refreshFailure) {}
}
