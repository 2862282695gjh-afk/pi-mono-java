/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.codingagent.runtimeapi.dto.PlanTextSnapshotDTO;

import org.junit.jupiter.api.Test;

/**
 * 计划标记流式解析的分片、结构与容量边界测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/19]
 * @since [br_eCampusCore 26.0.0]
 */
class PlanTextParserTest {
    @Test
    void shouldParseEveryCharacterSplitWithCrLfAndClosingTagAtEof() {
        String raw = "说明\r\n<proposed_plan>\r\n# 方案\r\n步骤\r\n</proposed_plan>";
        PlanTextParser parser = new PlanTextParser(1024);
        StringBuilder messages = new StringBuilder();
        StringBuilder plans = new StringBuilder();
        AtomicInteger starts = new AtomicInteger();

        raw.chars()
                .mapToObj(value -> String.valueOf((char) value))
                .forEach(chunk -> parser.accept(chunk, messages::append, starts::incrementAndGet, plans::append));
        PlanTextSnapshotDTO result = parser.finish();

        assertThat(starts).hasValue(1);
        assertThat(messages.toString()).isEqualTo("说明\r\n");
        assertThat(plans.toString()).isEqualTo("# 方案\r\n步骤\r\n");
        assertThat(result).isEqualTo(new PlanTextSnapshotDTO("说明\r\n", "# 方案\r\n步骤\r\n", true));
    }

    @Test
    void shouldReleaseOrdinaryAndPlanTextWithoutWaitingForLineEnd() {
        PlanTextParser parser = new PlanTextParser(1024);
        StringBuilder messages = new StringBuilder();
        StringBuilder plans = new StringBuilder();

        parser.accept("<proposed_plXrest", messages::append, () -> {}, plans::append);
        assertThat(messages.toString()).isEqualTo("<proposed_plXrest");
        parser.accept("\n<proposed_plan>\n", messages::append, () -> {}, plans::append);
        parser.accept("# unfinished line", messages::append, () -> {}, plans::append);

        assertThat(messages.toString()).isEqualTo("<proposed_plXrest\n");
        assertThat(plans.toString()).isEqualTo("# unfinished line");
    }

    @Test
    void shouldConcatenateTextBlocksForFinalSnapshot() {
        AssistantMessage assistant = assistant(
                List.of(new TextContent("说明\n<proposed_"), new TextContent("plan>\n# 方案\n步骤\n</proposed_plan>")));

        PlanTextSnapshotDTO result = PlanTextParser.parse(assistant, 1024);

        assertThat(result).isEqualTo(new PlanTextSnapshotDTO("说明\n", "# 方案\n步骤\n", true));
    }

    @Test
    void shouldRejectInvalidStructuresAndHeadings() {
        List<String> invalid = List.of(
                "</proposed_plan>\n",
                "<proposed_plan>\n# 方案\n",
                "<proposed_plan>\n</proposed_plan>",
                "<proposed_plan>\n## 二级\n</proposed_plan>",
                "<proposed_plan>\n# 一\n<proposed_plan>\n</proposed_plan>",
                "<proposed_plan>\n# 一\n</proposed_plan>\n<proposed_plan>\n# 二\n</proposed_plan>",
                "<proposed_plan>\n# 方案\n</proposed_plan>\n额外正文",
                "<proposed_plan>");

        for (String value : invalid) {
            assertThat(parse(value, 1024).valid()).as(value).isFalse();
        }
    }

    @Test
    void shouldRejectPlanBeyondUtf16LimitWithoutEmittingOverflow() {
        PlanTextParser parser = new PlanTextParser(4);
        StringBuilder plans = new StringBuilder();

        parser.accept("<proposed_plan>\n# 甲乙\n</proposed_plan>", ignored -> {}, () -> {}, plans::append);

        assertThat(plans).hasToString("# 甲乙");
        assertThat(parser.finish().valid()).isFalse();
    }

    @Test
    void shouldNotEmitAnEmptyPlanDeltaForMarkerOnlyBlock() {
        PlanTextParser parser = new PlanTextParser(1024);
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger deltas = new AtomicInteger();

        parser.accept(
                "<proposed_plan>\n</proposed_plan>",
                ignored -> {},
                starts::incrementAndGet,
                ignored -> deltas.incrementAndGet());

        assertThat(starts).hasValue(1);
        assertThat(deltas).hasValue(0);
        assertThat(parser.finish().valid()).isFalse();
    }

    private static PlanTextSnapshotDTO parse(String value, int limit) {
        PlanTextParser parser = new PlanTextParser(limit);
        parser.accept(value, ignored -> {}, () -> {}, ignored -> {});
        return parser.finish();
    }

    private static AssistantMessage assistant(List<com.campusclaw.ai.types.ContentBlock> content) {
        return new AssistantMessage(
                content, "api", "provider", "model", null, Usage.empty(), StopReason.TOOL_USE, null, 1L);
    }
}
