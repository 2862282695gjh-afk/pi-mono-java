/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.mate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import com.huawei.hicampus.claw.codingagent.common.client.mate.MateToolMeta;
import com.huawei.hicampus.claw.common.constant.ClawConstants;

/**
 * 保存单个 Session 的 Mate 工具来源快照和完整名称解析索引。
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
        Map<String, MateToolResolutionDTO> sourceSnapshot = toNameIndex(tools);
        synchronized (stateLock) {
            Map<MateToolSource, Map<String, MateToolResolutionDTO>> updated = new LinkedHashMap<>(state.sources());
            updated.put(source, sourceSnapshot);
            Map<String, MateToolResolutionDTO> complete = mergeSources(updated);
            state = new CacheState(copySources(updated), complete, state.fullRefreshGeneration(), null);
        }
    }

    /**
     * 按名称查询当前完整有效快照。
     *
     * @param toolName 工具名称
     * @return 内部工具标识、技术名称与展示名称快照；未命中时为空
     */
    public MateToolResolutionDTO lookup(String toolName) {
        return state.complete().get(toolName);
    }

    /**
     * 缓存未命中时执行一次完整发现；并发 miss 共享同一轮刷新结果。
     *
     * @param toolName 待解析名称
     * @param loader 完整来源加载器
     * @return 内部工具标识、技术名称与展示名称快照；刷新后仍未命中时为空
     */
    public MateToolResolutionDTO resolveOrRefresh(
            String toolName, Supplier<Map<MateToolSource, List<MateToolMeta>>> loader) {
        CacheState observed = state;
        MateToolResolutionDTO cached = observed.complete().get(toolName);
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
        Map<MateToolSource, Map<String, MateToolResolutionDTO>> sources = new LinkedHashMap<>();
        discovered.forEach((source, tools) -> sources.put(source, toNameIndex(tools)));
        Map<String, MateToolResolutionDTO> complete = mergeSources(sources);
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

    private static Map<String, MateToolResolutionDTO> toNameIndex(List<MateToolMeta> tools) {
        Map<String, MateToolResolutionDTO> index = new LinkedHashMap<>();
        for (MateToolMeta tool : tools == null ? List.<MateToolMeta>of() : tools) {
            if (tool.toolName() == null
                    || tool.toolName().isBlank()
                    || tool.toolId() == null
                    || !ClawConstants.Tool.ID_PATTERN.matcher(tool.toolId()).matches()) {
                throw new IllegalStateException("Mate tool metadata is incomplete");
            }
            MateToolResolutionDTO current =
                    new MateToolResolutionDTO(tool.toolId(), tool.toolName(), normalizeDisplayName(tool.displayName()));
            MateToolResolutionDTO previous = index.putIfAbsent(tool.toolName(), current);
            if (previous != null && !previous.toolId().equals(tool.toolId())) {
                throw new IllegalStateException("Duplicate Mate tool name maps to different ids");
            }
            if (previous != null && !java.util.Objects.equals(previous.displayName(), current.displayName())) {
                index.put(tool.toolName(), new MateToolResolutionDTO(tool.toolId(), tool.toolName(), null));
            }
        }
        return Map.copyOf(index);
    }

    private static Map<String, MateToolResolutionDTO> mergeSources(
            Map<MateToolSource, Map<String, MateToolResolutionDTO>> sources) {
        Map<String, MateToolResolutionDTO> complete = new LinkedHashMap<>();
        for (Map<String, MateToolResolutionDTO> source : sources.values()) {
            source.forEach((name, resolution) -> mergeName(complete, name, resolution));
        }
        return Map.copyOf(complete);
    }

    private static void mergeName(
            Map<String, MateToolResolutionDTO> complete, String name, MateToolResolutionDTO resolution) {
        MateToolResolutionDTO previous = complete.putIfAbsent(name, resolution);
        if (previous != null && !previous.toolId().equals(resolution.toolId())) {
            throw new IllegalStateException("Duplicate Mate tool name maps to different ids");
        }
        if (previous != null && !java.util.Objects.equals(previous.displayName(), resolution.displayName())) {
            complete.put(name, new MateToolResolutionDTO(resolution.toolId(), name, null));
        }
    }

    private static String normalizeDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        return displayName.strip();
    }

    private static Map<MateToolSource, Map<String, MateToolResolutionDTO>> copySources(
            Map<MateToolSource, Map<String, MateToolResolutionDTO>> sources) {
        return Map.copyOf(sources);
    }

    private record CacheState(
            Map<MateToolSource, Map<String, MateToolResolutionDTO>> sources,
            Map<String, MateToolResolutionDTO> complete,
            long fullRefreshGeneration,
            RuntimeException refreshFailure) {}
}
