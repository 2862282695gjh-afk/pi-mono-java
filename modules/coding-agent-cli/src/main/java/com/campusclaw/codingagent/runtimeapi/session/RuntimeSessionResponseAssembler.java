/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.session;

import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.vo.CostResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.CreateSessionResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.GetSessionResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.UsageResponseVO;

import org.springframework.stereotype.Component;

/**
 * 统一把 Session DTO 组装为只读响应 VO 和强 ETag。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeSessionResponseAssembler {
    private final SessionEtagFactory etagFactory;

    public RuntimeSessionResponseAssembler(SessionEtagFactory etagFactory) {
        this.etagFactory = etagFactory;
    }

    public RuntimeSessionView<CreateSessionResponseVO> createView(RuntimeSessionDTO session) {
        var resource = new CreateSessionResponseVO(
                session.getId(),
                session.getAgentId(),
                session.getModelId(),
                session.getState(),
                session.isThinking(),
                usage(session.getLifetimeUsage()),
                session.getCreatedAt());
        return new RuntimeSessionView<>(resource, etag(session));
    }

    public RuntimeSessionView<GetSessionResponseVO> getView(RuntimeSessionDTO session) {
        var resource = new GetSessionResponseVO(
                session.getId(),
                session.getAgentId(),
                session.getModelId(),
                session.getState(),
                session.isThinking(),
                usage(session.getLifetimeUsage()),
                session.getCreatedAt(),
                session.getUpdatedAt());
        return new RuntimeSessionView<>(resource, etag(session));
    }

    private String etag(RuntimeSessionDTO session) {
        return etagFactory.create(session.getId(), session.getResourceVersion());
    }

    private static UsageResponseVO usage(com.campusclaw.ai.types.Usage usage) {
        var value = usage == null ? com.campusclaw.ai.types.Usage.empty() : usage;
        var cost = value.cost() == null ? com.campusclaw.ai.types.Cost.empty() : value.cost();
        return new UsageResponseVO(
                value.input(),
                value.output(),
                value.cacheRead(),
                value.cacheWrite(),
                value.totalTokens(),
                new CostResponseVO(cost.input(), cost.output(), cost.cacheRead(), cost.cacheWrite(), cost.total()));
    }
}
