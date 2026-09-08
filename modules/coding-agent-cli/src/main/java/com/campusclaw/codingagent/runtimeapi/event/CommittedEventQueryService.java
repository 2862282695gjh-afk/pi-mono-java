/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.util.List;

import com.campusclaw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.vo.ListSessionEventsResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.SessionEventResponseVO;
import com.campusclaw.common.constant.ClawConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 查询当前 Session 分支的权威公共事件。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class CommittedEventQueryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommittedEventQueryService.class);

    private final RuntimeSessionRepository repository;

    private final CommittedEventProjection projection;

    public CommittedEventQueryService(RuntimeSessionRepository repository, CommittedEventProjection projection) {
        this.repository = repository;
        this.projection = projection;
    }

    public ListSessionEventsResponseVO list(String sessionId, Integer limitValue, Long pageValue) {
        try {
            int limit = normalizeLimit(limitValue);
            long page = normalizePage(pageValue);
            long offset = calculateOffset(page, limit);
            List<CommittedEventDTO> events = repository
                    .findEventPage(sessionId, offset, limit + 1)
                    .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND));
            return pageOf(events, limit, page);
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            logFailure(sessionId, error);
            throw new RuntimeApiException(RuntimeErrorCode.EVENT_LIST_FAILED);
        }
    }

    private ListSessionEventsResponseVO pageOf(List<CommittedEventDTO> entries, int limit, long page) {
        boolean more = entries.size() > limit;
        List<CommittedEventDTO> pageEntries = more ? entries.subList(0, limit) : entries;
        List<SessionEventResponseVO> events =
                pageEntries.stream().map(projection::project).toList();
        return new ListSessionEventsResponseVO(events, more ? nextPage(page) : null);
    }

    private static int normalizeLimit(Integer value) {
        int limit = value == null ? ClawConstants.RuntimeApi.DEFAULT_EVENT_PAGE_LIMIT : value;
        if (limit < 1 || limit > ClawConstants.RuntimeApi.MAX_EVENT_PAGE_LIMIT) {
            throw invalidQuery();
        }
        return limit;
    }

    private static long normalizePage(Long value) {
        long page = value == null ? 1L : value;
        if (page < 1L) {
            throw invalidQuery();
        }
        return page;
    }

    private static long calculateOffset(long page, int limit) {
        try {
            return Math.multiplyExact(page - 1L, limit);
        } catch (ArithmeticException error) {
            throw invalidQuery();
        }
    }

    private static long nextPage(long page) {
        try {
            return Math.addExact(page, 1L);
        } catch (ArithmeticException error) {
            throw invalidQuery();
        }
    }

    private static RuntimeApiException invalidQuery() {
        return new RuntimeApiException(RuntimeErrorCode.INVALID_EVENT_LIST_QUERY);
    }

    private static void logFailure(String sessionId, RuntimeException error) {
        RuntimeErrorCode errorCode = RuntimeErrorCode.EVENT_LIST_FAILED;
        LOGGER.atError()
                .addKeyValue("event", "campusclaw.failure")
                .addKeyValue("operation", "runtime.events.list")
                .addKeyValue("errorCode", errorCode.name())
                .addKeyValue("sessionId", sessionId)
                .setCause(error)
                .log("CampusClaw failure: operation={}, errorCode={}", "runtime.events.list", errorCode.name());
    }
}
