/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.StampedLock;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolMeta;

/**
 * Mate 工具名到标识的会话级映射缓存。
 *
 * <p>不同 agent 绑定的工具列表不同,因此缓存以会话为边界:每个 agent
 * session 持有一对独立的 Mate 工具实例与本缓存实例,实例私有即隔离,
 * 不需要显式 sessionId。{@code listMateTool} 每次查询后调用
 * {@link #refresh(List)} 硬性全量替换映射(而非增量合并),保证缓存始终
 * 反映该 agent 当前的绑定集;{@code callMateTool} 以工具名经
 * {@link #lookupToolId(String)} 取执行用标识。
 *
 * <p>非会话场景(Spring 单例工具)不持有缓存,callMateTool 直接拒绝并
 * 提示先调用 listMateTool。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/22]
 * @since [br_eCampusCore 26.0.0]
 */
public class MateToolSessionCache {

    private final Map<String, String> toolIdByName = new HashMap<>();

    private final StampedLock lock = new StampedLock();

    /**
     * 以一次查询结果硬性全量替换映射。
     *
     * @param metas listMateTool 本次查询到的工具元数据
     */
    public void refresh(List<MateToolMeta> metas) {
        long stamp = lock.writeLock();
        try {
            toolIdByName.clear();
            if (metas != null) {
                for (MateToolMeta meta : metas) {
                    if (meta.toolId() == null) {
                        continue;
                    }

                    // A blank toolName falls back to the id as the call key,
                    // matching what ListMateTool displays to the model.
                    String callKey =
                            meta.toolName() != null && !meta.toolName().isBlank() ? meta.toolName() : meta.toolId();
                    toolIdByName.put(callKey, meta.toolId());
                }
            }
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * 按工具名查执行用标识。
     *
     * @param toolName 工具名
     * @return 对应的工具标识;未命中(未刷新或不在当前绑定集)返回 {@code null}
     */
    public String lookupToolId(String toolName) {
        long stamp = lock.tryOptimisticRead();
        String toolId = toolIdByName.get(toolName);
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                toolId = toolIdByName.get(toolName);
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return toolId;
    }
}
