/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.session;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeLifetimeUsageDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.CreateSessionResponseVO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.GetSessionResponseVO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.LifetimeCostResponseVO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.LifetimeUsageResponseVO;

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
                session.getDisplayName(),
                session.getModelId(),
                session.getState(),
                session.isThinking(),
                lifetimeUsage(session.getLifetimeUsage()),
                session.getCreatedAt());
        return new RuntimeSessionView<>(resource, etag(session));
    }

    public RuntimeSessionView<GetSessionResponseVO> getView(RuntimeSessionDTO session) {
        var resource = new GetSessionResponseVO(
                session.getId(),
                session.getAgentId(),
                session.getDisplayName(),
                session.getModelId(),
                session.getState(),
                session.isThinking(),
                lifetimeUsage(session.getLifetimeUsage()),
                session.getCreatedAt(),
                session.getUpdatedAt());
        return new RuntimeSessionView<>(resource, etag(session));
    }

    private String etag(RuntimeSessionDTO session) {
        return etagFactory.create(session.getId(), session.getResourceVersion());
    }

    private LifetimeUsageResponseVO lifetimeUsage(RuntimeLifetimeUsageDTO usage) {
        var cost = new LifetimeCostResponseVO(
                usage.getCostInput(),
                usage.getCostOutput(),
                usage.getCostCacheRead(),
                usage.getCostCacheWrite(),
                usage.getCostTotal());
        return new LifetimeUsageResponseVO(
                usage.getInput(),
                usage.getOutput(),
                usage.getCacheRead(),
                usage.getCacheWrite(),
                usage.getTotalTokens(),
                cost);
    }
}
