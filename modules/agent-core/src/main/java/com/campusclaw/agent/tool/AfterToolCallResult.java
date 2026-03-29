package com.campusclaw.agent.tool;

import com.campusclaw.ai.types.ContentBlock;

import java.util.List;

/**
 * Optional overrides returned from the after-tool-call hook.
 */
public record AfterToolCallResult(
    List<ContentBlock> content,
    Object details,
    Boolean isError
) {

    public static AfterToolCallResult noOverride() {
        return new AfterToolCallResult(null, null, null);
    }
}
