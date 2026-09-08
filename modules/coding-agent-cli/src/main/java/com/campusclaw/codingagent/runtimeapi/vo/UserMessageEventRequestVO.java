/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.campusclaw.codingagent.runtimeapi.event.CommittedEventType;
import com.campusclaw.common.constant.ClawConstants;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * user.message 的有序文本和文件内容请求。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
@JsonDeserialize(using = JsonDeserializer.None.class)
public final class UserMessageEventRequestVO implements SessionUserEventRequestVO {
    @Valid
    @NotNull
    @Size(min = 1, max = 5)
    private List<@NotNull @Valid UserMessageContentRequestVO> content;

    @Override
    public String getType() {
        return CommittedEventType.USER_MESSAGE.value();
    }

    @JsonSetter("type")
    public void readType(JsonNode value) {
        requireExactType(value, getType());
    }

    @AssertTrue
    @JsonIgnore
    public boolean isContentOrderValid() {
        if (content == null) {
            return true;
        }
        Set<String> fileIds = new HashSet<>();
        for (int index = 0; index < content.size(); index++) {
            UserMessageContentRequestVO block = content.get(index);
            if (block instanceof UserMessageContentRequestVO.TextRequestVO && index != 0) {
                return false;
            }
            if (block instanceof UserMessageContentRequestVO.FileRequestVO file && !fileIds.add(file.getFileId())) {
                return false;
            }
        }
        return fileIds.size() <= ClawConstants.RuntimeApi.MAX_EVENT_FILE_IDS;
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("unknown user.message field");
    }

    private static void requireExactType(JsonNode value, String expected) {
        if (value == null || !value.isTextual() || !expected.equals(value.textValue())) {
            throw new IllegalArgumentException("user event type does not match request fields");
        }
    }
}
