/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommittedEventQueryServiceTest {
    private static final String SESSION_ID = "session-query";

    private RuntimeSessionRepository repository;

    private CommittedEventProjection projection;

    private CommittedEventQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(RuntimeSessionRepository.class);
        projection = mock(CommittedEventProjection.class);
        service = new CommittedEventQueryService(repository, projection);
    }

    @Test
    void shouldReturnNextIntegerPageWhenLookAheadExists() {
        CommittedEventDTO first = committedEvent("event-1");
        CommittedEventDTO second = committedEvent("event-2");
        CommittedEventDTO lookAhead = committedEvent("event-3");
        SessionEventResponseVO firstResponse = response("event-1");
        SessionEventResponseVO secondResponse = response("event-2");
        when(repository.findEventPage(SESSION_ID, 4L, 3)).thenReturn(Optional.of(List.of(first, second, lookAhead)));
        when(projection.project(first)).thenReturn(firstResponse);
        when(projection.project(second)).thenReturn(secondResponse);

        var page = service.list(SESSION_ID, 2, 3L);

        assertThat(page.getEvents()).containsExactly(firstResponse, secondResponse);
        assertThat(page.getNextPage()).isEqualTo(4L);
        verify(projection, never()).project(lookAhead);
    }

    @Test
    void shouldApplyDefaultsAndReturnEmptyOutOfRangePage() {
        when(repository.findEventPage(SESSION_ID, 2450L, 51)).thenReturn(Optional.of(List.of()));

        var page = service.list(SESSION_ID, null, 50L);

        assertThat(page.getEvents()).isEmpty();
        assertThat(page.getNextPage()).isNull();
    }

    @Test
    void shouldReportMissingSession() {
        when(repository.findEventPage(SESSION_ID, 0L, 51)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(SESSION_ID, null, null))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.SESSION_NOT_FOUND));
    }

    @Test
    void shouldRejectInvalidRangeAndOverflowBeforeRepositoryRead() {
        assertInvalid(0, 1L);
        assertInvalid(201, 1L);
        assertInvalid(200, 0L);
        assertInvalid(200, Long.MAX_VALUE);

        verify(repository, never()).findEventPage(any(), anyLong(), anyInt());
    }

    @Test
    void shouldMapProjectionFailureToStableListError() {
        CommittedEventDTO event = committedEvent("event-1");
        when(repository.findEventPage(SESSION_ID, 0L, 51)).thenReturn(Optional.of(List.of(event)));
        when(projection.project(event)).thenThrow(new IllegalArgumentException("unknown type"));

        assertThatThrownBy(() -> service.list(SESSION_ID, null, null))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.EVENT_LIST_FAILED));
    }

    private void assertInvalid(Integer limit, Long page) {
        assertThatThrownBy(() -> service.list(SESSION_ID, limit, page))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.INVALID_EVENT_LIST_QUERY));
    }

    private static CommittedEventDTO committedEvent(String eventId) {
        CommittedEventDTO event = new CommittedEventDTO();
        event.setEventId(eventId);
        return event;
    }

    private static SessionEventResponseVO response(String eventId) {
        var details = new SessionEventResponseVO.UserInterruptResponseVO("root");
        return new SessionEventResponseVO(eventId, "user.interrupt", "2026-09-08T01:00:00.000Z", details);
    }
}
