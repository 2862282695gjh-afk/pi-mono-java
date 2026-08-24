/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.campusclaw.ai.types.StopReason;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionState;
import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * 统一生成 Session 执行结束时的公共 SSE 事件。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeTerminalEventFactory {
    private final MessageSource messageSource;

    public RuntimeTerminalEventFactory(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public void emit(
            RuntimeEventStream stream,
            RuntimeActiveExecution execution,
            StopReason reason,
            Throwable failure,
            Locale locale) {
        if (failure != null || reason == StopReason.ERROR || execution.timedOut()) {
            emitError(stream, locale);
            return;
        }
        stream.emit(new RuntimeSseEventVO(
                null,
                RuntimeEventType.SESSION_STATUS_IDLE.value(),
                Map.of("status", RuntimeSessionState.IDLE.value())));
        String value = execution.abortRequested() || reason == StopReason.ABORTED ? "aborted" : "completed";
        stream.emit(new RuntimeSseEventVO(null, RuntimeEventType.STREAM_END.value(), Map.of("reason", value)));
    }

    private void emitError(RuntimeEventStream stream, Locale locale) {
        RuntimeErrorCode code = RuntimeErrorCode.SESSION_EXECUTION_FAILED;
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("resCode", code.name());
        data.put("resMsg", messageSource.getMessage(code.messageKey(), null, locale));
        stream.emit(new RuntimeSseEventVO(null, RuntimeEventType.STREAM_ERROR.value(), data));
    }
}
