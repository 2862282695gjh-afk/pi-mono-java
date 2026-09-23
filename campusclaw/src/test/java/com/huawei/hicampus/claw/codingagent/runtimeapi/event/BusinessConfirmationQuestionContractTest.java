/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.AgentQuestionResponseVO;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionAnswerDTO;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionRequestParser;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionRequestSnapshotDTO;
import com.huawei.hicampus.claw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

/**
 * 验证业务确认问题在 Entry、公共事件、历史投影和答案校验之间保持同一契约。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/17]
 * @since [br_eCampusCore 26.0.0]
 */
class BusinessConfirmationQuestionContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void payloadShouldPreserveLabelAndContextAcrossPersistenceAndHistory() throws Exception {
        var context = MAPPER.readTree("{\"nested\":[null,{\"值\":\"  原样  \"}]}");
        var snapshot = QuestionRequestSnapshotDTO.businessConfirmation("  label  ", context);
        var codec = new RuntimeEntryCodec(MAPPER, mock(MessageSource.class));
        RuntimeEntryDTO entry =
                codec.questionEntry("session-1", "entry-1", "call-1", snapshot, OffsetDateTime.now(ZoneOffset.UTC));
        var factory = new RuntimeCommittedEventFactory(MAPPER, mock(MessageSource.class));

        CommittedEventDTO committed = factory.agentQuestion(entry, "question-1", "call-1", snapshot, "source-1");
        var details = (AgentQuestionResponseVO)
                new CommittedEventProjection(MAPPER).project(committed).getDetails();

        assertThat(MAPPER.readTree(entry.getPayload()).path("label").textValue())
                .isEqualTo("  label  ");
        assertThat(MAPPER.readTree(committed.getPayload()).path("context")).isEqualTo(context);
        assertThat(details.getLabel()).isEqualTo("  label  ");
        assertThat(details.getContext()).isEqualTo(context);
        assertThat(details.getQuestions()).isNull();
    }

    @Test
    void answerShouldRequireTheFixedTextSlotAndKeepOldQuestionsCompatible() throws Exception {
        RuntimeSessionRepository sessions = mock(RuntimeSessionRepository.class);
        var parser = new QuestionRequestParser(MAPPER);
        var service = new RuntimeUserQuestionService(sessions, parser, MAPPER);
        when(sessions.findCommittedEvent("session-1", "question-1"))
                .thenReturn(Optional.of(businessConfirmationEvent("{\"key\":null}")));
        var answer =
                new QuestionAnswerDTO(ClawConstants.RuntimeApi.BUSINESS_CONFIRMATION_ANSWER_ID, List.of(), "  真实回答  ");

        var normalized = service.normalizeCurrent("session-1", "question-1", List.of(answer));

        assertThat(normalized.answers().getFirst().custom()).isEqualTo("真实回答");
        assertThatThrownBy(() -> service.normalizeCurrent(
                        "session-1", "question-1", List.of(new QuestionAnswerDTO("other", List.of(), "answer"))))
                .isInstanceOf(RuntimeApiException.class);
    }

    private static CommittedEventDTO businessConfirmationEvent(String context) {
        CommittedEventDTO event = new CommittedEventDTO();
        event.setSessionId("session-1");
        event.setEventId("question-1");
        event.setAnchorEntryId("entry-1");
        event.setType(CommittedEventType.AGENT_QUESTION.value());
        event.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        event.setPayload("{\"sourceEventId\":\"source-1\",\"toolCallId\":\"call-1\",\"label\":\"label\",\"context\":"
                + context + "}");
        return event;
    }
}
