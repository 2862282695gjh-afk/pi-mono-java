/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.ai.stream;

import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.ToolCall;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 大模型 Assistant 消息流产生的事件联合类型。
 *
 * <p>事件遵循以下生命周期：
 * <ol>
 *   <li>{@link StartEvent} — 消息流开始</li>
 *   <li>正文、原始推理和工具调用均使用开始、增量和结束事件</li>
 *   <li>{@link DoneEvent} 或 {@link ErrorEvent} — 消息流结束</li>
 * </ol>
 *
 * <p>使用 {@code type} 作为 JSON 多态序列化的类型标识。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AssistantMessageEvent.StartEvent.class, name = "start"),
    @JsonSubTypes.Type(value = AssistantMessageEvent.TextStartEvent.class, name = "text_start"),
    @JsonSubTypes.Type(value = AssistantMessageEvent.TextDeltaEvent.class, name = "text_delta"),
    @JsonSubTypes.Type(value = AssistantMessageEvent.TextEndEvent.class, name = "text_end"),
    @JsonSubTypes.Type(value = AssistantMessageEvent.ThinkingStartEvent.class, name = "thinking_start"),
    @JsonSubTypes.Type(value = AssistantMessageEvent.ThinkingDeltaEvent.class, name = "thinking_delta"),
    @JsonSubTypes.Type(value = AssistantMessageEvent.ThinkingEndEvent.class, name = "thinking_end"),
    @JsonSubTypes.Type(value = AssistantMessageEvent.ToolCallStartEvent.class, name = "toolcall_start"),
    @JsonSubTypes.Type(value = AssistantMessageEvent.ToolCallDeltaEvent.class, name = "toolcall_delta"),
    @JsonSubTypes.Type(value = AssistantMessageEvent.ToolCallEndEvent.class, name = "toolcall_end"),
    @JsonSubTypes.Type(value = AssistantMessageEvent.DoneEvent.class, name = "done"),
    @JsonSubTypes.Type(value = AssistantMessageEvent.ErrorEvent.class, name = "error")
})
public sealed interface AssistantMessageEvent
        permits AssistantMessageEvent.StartEvent,
                AssistantMessageEvent.TextStartEvent,
                AssistantMessageEvent.TextDeltaEvent,
                AssistantMessageEvent.TextEndEvent,
                AssistantMessageEvent.ThinkingStartEvent,
                AssistantMessageEvent.ThinkingDeltaEvent,
                AssistantMessageEvent.ThinkingEndEvent,
                AssistantMessageEvent.ToolCallStartEvent,
                AssistantMessageEvent.ToolCallDeltaEvent,
                AssistantMessageEvent.ToolCallEndEvent,
                AssistantMessageEvent.DoneEvent,
                AssistantMessageEvent.ErrorEvent {

    // 消息流生命周期。

    /**
     * 消息流开始，并携带初始 Assistant 消息快照。
     */
    record StartEvent(@JsonProperty("partial") AssistantMessage partial) implements AssistantMessageEvent {}

    // 正文事件。

    /**
     * 指定索引处的正文内容块开始。
     */
    record TextStartEvent(
            @JsonProperty("contentIndex") int contentIndex, @JsonProperty("partial") AssistantMessage partial)
            implements AssistantMessageEvent {}

    /**
     * 正文内容增量。
     */
    record TextDeltaEvent(
            @JsonProperty("contentIndex") int contentIndex,
            @JsonProperty("delta") String delta,
            @JsonProperty("partial") AssistantMessage partial)
            implements AssistantMessageEvent {}

    /**
     * 指定索引处的正文内容块完成。
     */
    record TextEndEvent(
            @JsonProperty("contentIndex") int contentIndex,
            @JsonProperty("content") String content,
            @JsonProperty("partial") AssistantMessage partial)
            implements AssistantMessageEvent {}

    // 模型返回的可读原始推理事件。

    /**
     * 指定索引处的原始推理内容块开始。
     */
    record ThinkingStartEvent(
            @JsonProperty("contentIndex") int contentIndex, @JsonProperty("partial") AssistantMessage partial)
            implements AssistantMessageEvent {}

    /**
     * 模型返回的原始推理文本增量。
     */
    record ThinkingDeltaEvent(
            @JsonProperty("contentIndex") int contentIndex,
            @JsonProperty("delta") String delta,
            @JsonProperty("partial") AssistantMessage partial)
            implements AssistantMessageEvent {}

    /**
     * 指定索引处的原始推理内容块完成。
     */
    record ThinkingEndEvent(
            @JsonProperty("contentIndex") int contentIndex,
            @JsonProperty("content") String content,
            @JsonProperty("partial") AssistantMessage partial)
            implements AssistantMessageEvent {}

    // 工具调用事件。

    /**
     * 指定索引处的工具调用内容块开始。
     */
    record ToolCallStartEvent(
            @JsonProperty("contentIndex") int contentIndex, @JsonProperty("partial") AssistantMessage partial)
            implements AssistantMessageEvent {}

    /**
     * 工具调用参数的 JSON 片段增量。
     */
    record ToolCallDeltaEvent(
            @JsonProperty("contentIndex") int contentIndex,
            @JsonProperty("delta") String delta,
            @JsonProperty("partial") AssistantMessage partial)
            implements AssistantMessageEvent {}

    /**
     * 指定索引处的工具调用完成，并携带解析后的 ToolCall。
     */
    record ToolCallEndEvent(
            @JsonProperty("contentIndex") int contentIndex,
            @JsonProperty("toolCall") ToolCall toolCall,
            @JsonProperty("partial") AssistantMessage partial)
            implements AssistantMessageEvent {}

    // 完成事件。

    /**
     * 消息流正常完成。
     *
     * @param reason 停止原因（stop、length 或 toolUse）
     * @param message 最终完整 Assistant 消息
     */
    record DoneEvent(@JsonProperty("reason") StopReason reason, @JsonProperty("message") AssistantMessage message)
            implements AssistantMessageEvent {}

    /**
     * 消息流因错误结束。
     *
     * @param reason 错误原因（error 或 aborted）
     * @param error 包含错误信息的 Assistant 消息
     */
    record ErrorEvent(@JsonProperty("reason") String reason, @JsonProperty("error") AssistantMessage error)
            implements AssistantMessageEvent {}
}
