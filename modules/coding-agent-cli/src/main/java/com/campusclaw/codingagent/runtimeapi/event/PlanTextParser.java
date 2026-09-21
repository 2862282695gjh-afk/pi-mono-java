/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.util.Objects;
import java.util.function.Consumer;

import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.codingagent.runtimeapi.dto.PlanTextSnapshotDTO;

/**
 * 跨正文块和增量识别唯一计划标记块，并即时分离普通正文与计划正文。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/19]
 * @since [br_eCampusCore 26.0.0]
 */
public final class PlanTextParser {
    private static final String OPEN = "<proposed_plan>";

    private static final String CLOSE = "</proposed_plan>";

    private final int maxPlanCharacters;

    private final StringBuilder message = new StringBuilder();

    private final StringBuilder plan = new StringBuilder();

    private final StringBuilder candidate = new StringBuilder();

    private State state = State.BEFORE;

    private boolean lineStart = true;

    private boolean invalid;

    private boolean started;

    public PlanTextParser(int maxPlanCharacters) {
        if (maxPlanCharacters < 1) {
            throw new IllegalArgumentException("plan character limit must be positive");
        }
        this.maxPlanCharacters = maxPlanCharacters;
    }

    public void accept(String chunk, Consumer<String> messageSink, Runnable planStart, Consumer<String> planSink) {
        if (chunk == null || chunk.isEmpty() || invalid) {
            return;
        }
        Objects.requireNonNull(messageSink, "messageSink");
        Objects.requireNonNull(planStart, "planStart");
        Objects.requireNonNull(planSink, "planSink");
        StringBuilder messageDelta = new StringBuilder();
        StringBuilder planDelta = new StringBuilder();
        for (int index = 0; index < chunk.length() && !invalid; index++) {
            process(chunk.charAt(index), messageDelta, planDelta, planStart);
        }
        emit(messageSink, messageDelta);
        emit(planSink, planDelta);
    }

    public PlanTextSnapshotDTO finish() {
        finishCandidate();
        boolean valid = !invalid && state == State.AFTER && validPlan();
        return new PlanTextSnapshotDTO(message.toString(), plan.toString(), valid);
    }

    public static PlanTextSnapshotDTO parse(AssistantMessage message, int maxPlanCharacters) {
        PlanTextParser parser = new PlanTextParser(maxPlanCharacters);
        for (var block : message.content()) {
            if (block instanceof TextContent text) {
                parser.accept(text.text(), ignored -> {}, () -> {}, ignored -> {});
            }
        }
        return parser.finish();
    }

    private void process(char value, StringBuilder messageDelta, StringBuilder planDelta, Runnable planStart) {
        if (candidate.length() > 0 || (lineStart && value == '<')) {
            candidate.append(value);
            processCandidate(messageDelta, planDelta, planStart);
            return;
        }
        append(String.valueOf(value), messageDelta, planDelta);
    }

    private void processCandidate(StringBuilder messageDelta, StringBuilder planDelta, Runnable planStart) {
        String value = candidate.toString();
        if (isMarkerLine(value, OPEN)) {
            handleMarker(true, planStart);
        } else if (isMarkerLine(value, CLOSE)) {
            handleMarker(false, planStart);
        } else if (!isMarkerPrefix(value)) {
            candidate.setLength(0);
            append(value, messageDelta, planDelta);
        }
    }

    private void handleMarker(boolean opening, Runnable planStart) {
        candidate.setLength(0);
        lineStart = true;
        if (opening && state == State.BEFORE) {
            state = State.PLAN;
            started = true;
            planStart.run();
        } else if (!opening && state == State.PLAN) {
            state = State.AFTER;
        } else {
            invalid = true;
        }
    }

    private void append(String value, StringBuilder messageDelta, StringBuilder planDelta) {
        if (state == State.BEFORE) {
            message.append(value);
            messageDelta.append(value);
        } else if (state == State.PLAN) {
            appendPlan(value, planDelta);
        } else if (state == State.AFTER && !value.isBlank()) {
            invalid = true;
        }
        lineStart = value.charAt(value.length() - 1) == '\n';
    }

    private void appendPlan(String value, StringBuilder planDelta) {
        int remaining = maxPlanCharacters - plan.length();
        if (remaining <= 0) {
            invalid = true;
            return;
        }
        String accepted = value.length() <= remaining ? value : value.substring(0, remaining);
        plan.append(accepted);
        planDelta.append(accepted);
        if (accepted.length() != value.length()) {
            invalid = true;
        }
    }

    private void finishCandidate() {
        if (candidate.length() == 0 || invalid) {
            return;
        }
        String value = candidate.toString();
        candidate.setLength(0);
        if (CLOSE.equals(value) && state == State.PLAN) {
            state = State.AFTER;
            return;
        }
        append(value, new StringBuilder(), new StringBuilder());
        if (started && state == State.PLAN) {
            invalid = true;
        }
    }

    private boolean validPlan() {
        return !plan.isEmpty()
                && plan.toString()
                        .lines()
                        .filter(line -> !line.isBlank())
                        .findFirst()
                        .filter(line -> line.startsWith("# "))
                        .isPresent();
    }

    private static boolean isMarkerLine(String value, String marker) {
        return value.equals(marker + "\n") || value.equals(marker + "\r\n");
    }

    private static boolean isMarkerPrefix(String value) {
        return OPEN.startsWith(value)
                || CLOSE.startsWith(value)
                || (OPEN + "\r").startsWith(value)
                || (CLOSE + "\r").startsWith(value);
    }

    private static void emit(Consumer<String> sink, StringBuilder content) {
        if (!content.isEmpty()) {
            sink.accept(content.toString());
        }
    }

    private enum State {
        BEFORE,
        PLAN,
        AFTER
    }
}
