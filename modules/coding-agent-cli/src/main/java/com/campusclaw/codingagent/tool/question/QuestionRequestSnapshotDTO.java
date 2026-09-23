/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.question;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 单次用户问题调用的不可变载荷快照。
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public final class QuestionRequestSnapshotDTO {
    private final List<QuestionDTO> questions;

    private final String label;

    private final JsonNode context;

    private final boolean businessConfirmation;

    public QuestionRequestSnapshotDTO(List<QuestionDTO> questions) {
        this.questions = List.copyOf(questions);
        this.label = null;
        this.context = null;
        this.businessConfirmation = false;
    }

    private QuestionRequestSnapshotDTO(String label, JsonNode context) {
        this.questions = List.of();
        this.label = java.util.Objects.requireNonNull(label, "label");
        this.context = context == null ? null : context.deepCopy();
        this.businessConfirmation = true;
    }

    public static QuestionRequestSnapshotDTO businessConfirmation(String label, JsonNode context) {
        return new QuestionRequestSnapshotDTO(label, context);
    }

    public List<QuestionDTO> questions() {
        return questions;
    }

    public boolean isBusinessConfirmation() {
        return businessConfirmation;
    }

    public String label() {
        return label;
    }

    public boolean hasContext() {
        return context != null;
    }

    public JsonNode context() {
        return context == null ? null : context.deepCopy();
    }
}
