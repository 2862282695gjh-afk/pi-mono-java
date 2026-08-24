/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.ai.stream;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.AssistantMessage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.StopReason;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 以最终 AssistantMessage 为结果的 Assistant 消息事件流。
 *
 * <p>收到完成或错误事件时自动结束，并从终态事件提取最终消息。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public class AssistantMessageEventStream {

    private final EventStream<AssistantMessageEvent, AssistantMessage> delegate;

    /**
     * 创建 Assistant 消息事件流。
     */
    public AssistantMessageEventStream() {
        this.delegate =
                new EventStream<>(AssistantMessageEventStream::isTerminal, AssistantMessageEventStream::extractMessage);
    }

    private static boolean isTerminal(AssistantMessageEvent event) {
        return event instanceof AssistantMessageEvent.DoneEvent || event instanceof AssistantMessageEvent.ErrorEvent;
    }

    private static AssistantMessage extractMessage(AssistantMessageEvent event) {
        return switch (event) {
            case AssistantMessageEvent.DoneEvent e -> e.message();
            case AssistantMessageEvent.ErrorEvent e -> e.error();
            default ->
                throw new IllegalStateException("extractMessage called on non-terminal event: "
                        + event.getClass().getSimpleName());
        };
    }

    /**
     * 发送原始 Assistant 消息事件。
     *
     * @param event 要发送的事件
     */
    public void push(AssistantMessageEvent event) {
        delegate.push(event);
    }

    /**
     * 发送文本增量事件。
     *
     * @param contentIndex 文本内容块索引
     * @param delta 文本增量
     * @param partial 当前部分 Assistant 消息
     */
    public void pushTextDelta(int contentIndex, String delta, AssistantMessage partial) {
        delegate.push(new AssistantMessageEvent.TextDeltaEvent(contentIndex, delta, partial));
    }

    /**
     * 发送完成事件并结束流。
     *
     * @param reason 停止原因
     * @param message 最终 Assistant 消息
     */
    public void pushDone(StopReason reason, AssistantMessage message) {
        delegate.push(new AssistantMessageEvent.DoneEvent(reason, message));
    }

    /**
     * 发送错误事件并结束流。
     *
     * @param reason 错误原因
     * @param error 包含错误信息的 Assistant 消息
     */
    public void pushError(String reason, AssistantMessage error) {
        delegate.push(new AssistantMessageEvent.ErrorEvent(reason, error));
    }

    /**
     * 使用显式结果结束流，不额外发送事件。
     *
     * @param result 最终 Assistant 消息
     */
    public void end(AssistantMessage result) {
        delegate.end(result);
    }

    /**
     * 使用错误结束流。
     *
     * @param e 要传播的错误
     */
    public void error(Throwable e) {
        delegate.error(e);
    }

    /**
     * 获取 Assistant 消息事件 Flux。
     *
     * @return 事件 Flux
     */
    public Flux<AssistantMessageEvent> asFlux() {
        return delegate.asFlux();
    }

    /**
     * 注册订阅者取消时执行的动作。
     *
     * @param action 取消动作
     */
    public void onCancel(Runnable action) {
        delegate.onCancel(action);
    }

    /**
     * 获取最终 Assistant 消息 Mono。
     *
     * @return 最终消息 Mono
     */
    public Mono<AssistantMessage> result() {
        return delegate.result();
    }
}
