/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.controlplane.error;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebInputException;

import reactor.core.publisher.Mono;

/**
 * Translates exceptions thrown by control-plane handler functions into structured HTTP
 * error bodies. Serves the same role the servlet-era {@code @RestControllerAdvice} did,
 * but as a webflux {@link HandlerFilterFunction} that can be attached to any
 * {@code RouterFunction} via {@code .filter(...)}.
 *
 * <p>Body shape mirrors Spring Boot's default error response:
 * <pre>{@code
 * { "timestamp": "2026-06-18T12:00:00Z", "status": 404, "error": "Not Found",
 *   "message": "node not registered: node-x" }
 * }</pre>
 *
 * <p>{@link Clock} is injected so deterministic tests can pin the {@code timestamp}
 * value. The application-level convention is that no production code calls
 * {@link Instant#now()} directly.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class ControlPlaneExceptionHandler implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneExceptionHandler.class);

    private final Clock clock;

    /**
     * Spring constructor.
     *
     * @param clock UTC clock bean exported by {@code ControlPlaneConfiguration}
     */
    public ControlPlaneExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Mono<ServerResponse> filter(ServerRequest request, HandlerFunction<ServerResponse> next) {
        return next.handle(request).onErrorResume(this::translate);
    }

    private Mono<ServerResponse> translate(Throwable ex) {
        if (ex instanceof NoSuchElementException nse) {
            log.warn("resource not found: {}", nse.getMessage());
            return errorBody(HttpStatus.NOT_FOUND, nse.getMessage());
        }
        if (ex instanceof IllegalArgumentException iae) {
            log.warn("bad request: {}", iae.getMessage());
            return errorBody(HttpStatus.BAD_REQUEST, iae.getMessage());
        }

        // webflux wraps record compact-constructor failures from .bodyToMono(...) in
        // ServerWebInputException with DecodingException(IllegalArgumentException)
        // somewhere down the cause chain — surface the original message as 400.
        Throwable iaeRoot = findCauseOfType(ex, IllegalArgumentException.class);
        if (ex instanceof ServerWebInputException || ex instanceof DecodingException || iaeRoot != null) {
            String message = iaeRoot != null ? iaeRoot.getMessage() : ex.getMessage();
            log.warn("bad request: {}", message);
            return errorBody(HttpStatus.BAD_REQUEST, message);
        }
        log.error("unexpected control-plane error", ex);
        return errorBody(HttpStatus.INTERNAL_SERVER_ERROR, "internal error");
    }

    private static Throwable findCauseOfType(Throwable ex, Class<? extends Throwable> type) {
        Throwable current = ex;
        while (current != null) {
            if (type.isInstance(current)) {
                return current;
            }
            Throwable next = current.getCause();
            if (next == current) {
                return null;
            }
            current = next;
        }
        return null;
    }

    private Mono<ServerResponse> errorBody(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now(clock).toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message == null ? "" : message);
        return ServerResponse.status(status).bodyValue(body);
    }
}
