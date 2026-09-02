/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.ai.stream;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 把命令式事件发送桥接到 Reactor Flux 和 Mono 的线程安全事件流。
 *
 * <p>生产者通过 {@link #push(Object)} 发送事件；命中完成条件或显式调用结束方法后，事件流与结果流完成。
 * 订阅前发送的事件由单订阅者 Sink 缓存。
 *
 * @param <T> 事件类型
 * @param <R> 最终结果类型
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public class EventStream<T, R> {

    private final Predicate<T> isComplete;
    private final Function<T, R> extractResult;

    private final Sinks.Many<T> eventSink;
    private final Sinks.One<R> resultSink;
    private final Flux<T> eventFlux;
    private final Mono<R> resultMono;

    private final Object lock = new Object();
    private boolean done = false;
    private Runnable cancelAction = () -> {};
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /**
     * 创建事件流。
     *
     * @param isComplete 判断事件是否为终态
     * @param extractResult 从终态事件提取最终结果的函数
     */
    public EventStream(Predicate<T> isComplete, Function<T, R> extractResult) {
        this.isComplete = isComplete;
        this.extractResult = extractResult;
        this.eventSink = Sinks.many().unicast().onBackpressureBuffer();
        this.resultSink = Sinks.one();
        this.eventFlux = eventSink.asFlux().doOnCancel(this::cancel);
        this.resultMono = resultSink.asMono().doOnCancel(this::cancel);
    }

    /**
     * 向流中发送一个事件；终态事件会先交付订阅者，再结束事件流。
     *
     * @param event 要发送的事件
     */
    public void push(T event) {
        synchronized (lock) {
            if (done) {
                return;
            }

            if (isComplete.test(event)) {
                done = true;
                resultSink.tryEmitValue(extractResult.apply(event));
            }

            Sinks.EmitResult emitted = eventSink.tryEmitNext(event);
            if (!emitted.isSuccess()
                    && emitted != Sinks.EmitResult.FAIL_CANCELLED
                    && emitted != Sinks.EmitResult.FAIL_TERMINATED) {
                emitted.orThrow();
            }

            if (done) {
                eventSink.tryEmitComplete();
            }
        }
    }

    /**
     * 使用显式结果结束流，不额外发送事件。
     *
     * @param result 最终结果
     */
    public void end(R result) {
        synchronized (lock) {
            if (done) {
                return;
            }
            done = true;
            resultSink.tryEmitValue(result);
            eventSink.tryEmitComplete();
        }
    }

    /**
     * 不提供结果并结束流；尚未产生结果时，结果 Mono 为空完成。
     */
    public void end() {
        synchronized (lock) {
            if (done) {
                return;
            }
            done = true;
            resultSink.tryEmitEmpty();
            eventSink.tryEmitComplete();
        }
    }

    /**
     * 使用错误结束事件流和结果流。
     *
     * @param e 要传播的错误
     */
    public void error(Throwable e) {
        synchronized (lock) {
            if (done) {
                return;
            }
            done = true;
            resultSink.tryEmitError(e);
            eventSink.tryEmitError(e);
        }
    }

    /**
     * 获取只支持一个订阅者的事件 Flux。
     *
     * @return 事件 Flux
     */
    public Flux<T> asFlux() {
        return eventFlux;
    }

    /**
     * 注册事件或结果订阅被取消时执行的动作。
     *
     * @param action 取消动作
     */
    public void onCancel(Runnable action) {
        synchronized (lock) {
            cancelAction = action != null ? action : () -> {};
        }
    }

    private void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        Runnable action;
        synchronized (lock) {
            action = cancelAction;
        }
        action.run();
    }

    /**
     * 获取最终结果 Mono。
     *
     * <p>取消结果订阅会执行与事件订阅相同的幂等取消动作。
     *
     * @return 最终结果 Mono
     */
    public Mono<R> result() {
        return resultMono;
    }
}
