/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mcp;

import java.util.List;

import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.TextContent;

/**
 * Maps MCP content blocks to CampusClaw content blocks.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public final class McpContentMapper {

    private McpContentMapper() {}

    public static List<ContentBlock> toContentBlocks(List<McpContent> content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        return content.stream()
                .filter(block -> block != null && "text".equals(block.type()))
                .map(block -> new TextContent(block.text() != null ? block.text() : ""))
                .map(ContentBlock.class::cast)
                .toList();
    }
}
