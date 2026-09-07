/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.service.command;

import java.util.Locale;
import java.util.concurrent.CompletionStage;

import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.execution.CommandRuntimeInvocation;
import com.huawei.hicampus.claw.codingagent.runtimeapi.compaction.RuntimeCompactionCall;
import com.huawei.hicampus.claw.codingagent.runtimeapi.compaction.RuntimeCompactionService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeCompactionResultDTO;

/**
 * 一次性压缩调用作用域；交接前清除本地凭据引用，关闭作用域不取消已接受执行。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
public final class CompactionCommandInvocation implements CommandRuntimeInvocation, AutoCloseable {
    private final RuntimeCompactionService runtime;

    private MateCredentials credentials;

    private boolean consumed;

    private RuntimeCompactionCall call;

    CompactionCommandInvocation(RuntimeCompactionService runtime, MateCredentials credentials) {
        this.runtime = runtime;
        this.credentials = credentials;
    }

    @Override
    public CompletionStage<RuntimeCompactionResultDTO> invoke(String sessionId, Locale locale) {
        MateCredentials captured = claimCredentials();
        RuntimeCompactionCall started = runtime.start(sessionId, captured, locale);
        synchronized (this) {
            call = started;
        }
        return started.result();
    }

    private synchronized MateCredentials claimCredentials() {
        if (consumed) {
            throw new IllegalStateException("compaction invocation has already been consumed or closed");
        }
        consumed = true;
        MateCredentials captured = credentials;
        credentials = null;
        return captured;
    }

    public boolean interrupt() {
        RuntimeCompactionCall target;
        synchronized (this) {
            target = call;
        }
        return target != null && target.interrupt();
    }

    @Override
    public synchronized void close() {
        consumed = true;
        credentials = null;
    }
}
