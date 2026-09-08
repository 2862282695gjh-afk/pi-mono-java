/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import java.time.Clock;
import java.util.Locale;

import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;

import org.springframework.stereotype.Component;

/**
 * 使用统一持久化依赖创建 Session 压缩事件投影器。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeEventProjectorFactory {
    private final RuntimeSessionRepository repository;

    private final RuntimeEntryCodec codec;

    private final RuntimeCommittedEventFactory committedEvents;

    private final RuntimeEntryIdGenerator idGenerator;

    private final Clock clock;

    public RuntimeEventProjectorFactory(
            RuntimeSessionRepository repository,
            RuntimeEntryCodec codec,
            RuntimeCommittedEventFactory committedEvents,
            RuntimeEntryIdGenerator idGenerator,
            Clock clock) {
        this.repository = repository;
        this.codec = codec;
        this.committedEvents = committedEvents;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * 创建没有初始用户消息的压缩投影器，不负责命令准入、容量分配或压缩启动。
     *
     * @param holder 本次活动 Session
     * @param execution 已选择输出策略且在持久化前分配内部 Usage 运行身份的执行
     * @param locale 公共事件输出语言
     * @return 本次执行独享的压缩投影器
     */
    public RuntimeEventProjector createForCompaction(
            RuntimeSessionHolder holder, RuntimeActiveExecution execution, Locale locale) {
        return new RuntimeEventProjector(
                holder.sessionId(),
                repository,
                codec,
                committedEvents,
                idGenerator,
                execution.output(),
                clock,
                holder::abort,
                execution,
                locale);
    }
}
