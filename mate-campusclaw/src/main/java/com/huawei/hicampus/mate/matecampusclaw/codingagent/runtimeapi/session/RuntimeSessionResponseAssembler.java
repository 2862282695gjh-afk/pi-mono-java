/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.CreateSessionResponseVO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.GetSessionResponseVO;

import org.springframework.stereotype.Component;

/**
 * 统一把 Session DTO 组装为只读响应 VO 和强 ETag。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/19]
 * @since [br_eCampusCore 25.1.0_Next]
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
                session.getCreatedAt(),
                session.getUpdatedAt());
        return new RuntimeSessionView<>(resource, etag(session));
    }

    private String etag(RuntimeSessionDTO session) {
        return etagFactory.create(session.getId(), session.getResourceVersion());
    }
}
