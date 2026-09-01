/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.huawei.hicampus.claw.ai.types.Message;
import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.EventPageResponseVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 查询当前 Session 分支事件，并把持久化 Entry 恢复为 Agent 历史。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class RuntimeEventQueryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeEventQueryService.class);

    private static final int DEFAULT_LIMIT = 100;

    private static final int MAX_LIMIT = 200;

    private static final int RESTORE_BATCH_SIZE = 500;

    private final RuntimeSessionRepository repository;

    private final RuntimeEntryCodec codec;

    private final RuntimeEventCursorCodec cursorCodec;

    public RuntimeEventQueryService(
            RuntimeSessionRepository repository, RuntimeEntryCodec codec, RuntimeEventCursorCodec cursorCodec) {
        this.repository = repository;
        this.codec = codec;
        this.cursorCodec = cursorCodec;
    }

    public EventPageResponseVO list(String sessionId, String limitValue, String page, Locale locale) {
        try {
            int limit = parseLimit(limitValue);
            RuntimeSessionDTO session = requireSession(sessionId);
            boolean thinking = session.isThinking();
            long afterSeq = page == null ? 0 : cursorCodec.decode(page, sessionId, thinking);
            List<RuntimeEntryDTO> entries = repository.listCurrentBranch(sessionId, afterSeq, limit + 1, thinking);
            return pageOf(sessionId, entries, limit, thinking, locale);
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            RuntimeErrorCode errorCode = RuntimeErrorCode.EVENT_LIST_FAILED;
            LOGGER.atError()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "runtime.events.list")
                    .addKeyValue("errorCode", errorCode.name())
                    .addKeyValue("sessionId", sessionId)
                    .setCause(error)
                    .log("CampusClaw failure: operation={}, errorCode={}", "runtime.events.list", errorCode.name());
            throw new RuntimeApiException(errorCode);
        }
    }

    public List<Message> restoreHistory(String sessionId, Model model) {
        List<RuntimeEntryDTO> entries = new ArrayList<>();
        long afterSeq = 0L;
        while (true) {
            List<RuntimeEntryDTO> batch = repository.listCurrentBranchEntries(sessionId, afterSeq, RESTORE_BATCH_SIZE);
            entries.addAll(batch);
            if (batch.size() < RESTORE_BATCH_SIZE) {
                break;
            }
            afterSeq = batch.getLast().getEntrySeq();
        }
        return codec.toAgentMessages(entries, model);
    }

    private EventPageResponseVO pageOf(
            String sessionId, List<RuntimeEntryDTO> entries, int limit, boolean thinking, Locale locale) {
        boolean more = entries.size() > limit;
        List<RuntimeEntryDTO> pageEntries = more ? entries.subList(0, limit) : entries;
        List<java.util.Map<String, Object>> events = pageEntries.stream()
                .map(entry -> codec.toHistoryEvent(entry, locale))
                .toList();
        String nextPage =
                more ? cursorCodec.encode(sessionId, pageEntries.getLast().getEntrySeq(), thinking) : null;
        return new EventPageResponseVO(events, nextPage);
    }

    private RuntimeSessionDTO requireSession(String sessionId) {
        return repository
                .find(sessionId)
                .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND));
    }

    private static int parseLimit(String value) {
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(value);
            if (limit < 1 || limit > MAX_LIMIT) {
                throw new NumberFormatException("limit out of range");
            }
            return limit;
        } catch (NumberFormatException error) {
            RuntimeErrorCode errorCode = RuntimeErrorCode.INVALID_EVENT_LIST_QUERY;
            LOGGER.atWarn()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "runtime.events.limit.parse")
                    .addKeyValue("errorCode", errorCode.name())
                    .setCause(error)
                    .log(
                            "CampusClaw failure: operation={}, errorCode={}",
                            "runtime.events.limit.parse",
                            errorCode.name());
            throw new RuntimeApiException(errorCode);
        }
    }
}
