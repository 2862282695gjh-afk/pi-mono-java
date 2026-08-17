/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.web;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.RuntimeApiConstants;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * 把 CampusClaw Runtime HTTP V1 接口显式挂到 Reactor Netty 的路由配置。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Configuration
public class RuntimeApiRoutes {
    private static final String SESSIONS = RuntimeApiConstants.BASE_PATH + "/sessions/{session_id}";

    private static final String EVENTS = SESSIONS + "/events";

    @Bean
    public RouterFunction<ServerResponse> runtimeSessionRoutes(
            RuntimeSessionController controller,
            RuntimeEventController eventController,
            RuntimeErrorFilter errorFilter,
            RuntimeAuthFilter authFilter) {
        String createPath = RuntimeApiConstants.BASE_PATH + "/agents/{agent_id}/sessions";
        return route(POST(createPath), controller::create)
                .andRoute(GET(SESSIONS), controller::get)
                .andRoute(DELETE(SESSIONS), controller::delete)
                .andRoute(POST(EVENTS), eventController::submit)
                .andRoute(GET(EVENTS), eventController::list)
                .filter(authFilter)
                .filter(errorFilter);
    }
}
