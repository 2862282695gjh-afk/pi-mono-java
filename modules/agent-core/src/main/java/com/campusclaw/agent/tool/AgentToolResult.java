package com.campusclaw.agent.tool;

import com.campusclaw.ai.types.ContentBlock;

import java.util.List;

/**
 * Final or partial result emitted by an {@link AgentTool}.
 *
 * @param content user-visible content returned by the tool
 * @param details optional implementation-specific structured details
 */
public record AgentToolResult(
    List<ContentBlock> content,
    Object details
) {
}
