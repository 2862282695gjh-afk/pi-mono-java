/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.common.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.campusclaw.codingagent.common.dto.RequestHeaderInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST helper for calls to the Mate inner gateway ({@code mate.innerGWSerive}).
 *
 * <p>The internal gateway does not require credential headers; a default
 * {@code RequestHeaderInfo.builder().build()} is sufficient — its fields are
 * still mapped onto HTTP headers so the internal deployment can carry them.
 * Transport is the JDK HttpClient; responses are returned raw and decoded by
 * the caller (the {@code {resCode, resMsg, result}} envelope differs per
 * endpoint). The internal development may replace the transport with the
 * campuscommon RestUtil while keeping these signatures.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public class MateRestUtil {

    private static final Logger log = LoggerFactory.getLogger(MateRestUtil.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient http;

    /**
     * Creates a MateRestUtil with a default HTTP client.
     */
    public MateRestUtil() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    /**
     * Creates a MateRestUtil with the given HTTP client (tests inject stubs here).
     *
     * @param http the HTTP client to use
     */
    public MateRestUtil(HttpClient http) {
        this.http = http;
    }

    /**
     * POSTs a JSON body to {@code gwAddress + path} and returns the raw
     * response body.
     *
     * @param gwAddress Mate inner gateway base address
     * @param path API path on the gateway
     * @param headerInfo request header info mapped onto HTTP headers
     * @param jsonBody raw JSON request body
     * @return raw response body string
     * @throws IllegalStateException when the call fails, is interrupted, or
     *         returns an empty body
     */
    public String executePostRawRequest(String gwAddress, String path, RequestHeaderInfo headerInfo, String jsonBody) {
        String url = joinUrl(gwAddress, path);
        try {
            HttpRequest request = newRequestBuilder(url, headerInfo)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();
            return send(url, request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Mate gateway call interrupted: " + url, e);
        } catch (Exception e) {
            throw logAndWrap(url, e);
        }
    }

    /**
     * GETs {@code gwAddress + path} and returns the raw response body.
     *
     * @param gwAddress Mate inner gateway base address
     * @param path API path on the gateway (may contain path variables)
     * @param headerInfo request header info mapped onto HTTP headers
     * @return raw response body string
     * @throws IllegalStateException when the call fails, is interrupted, or
     *         returns an empty body
     */
    public String executeGetRawRequest(String gwAddress, String path, RequestHeaderInfo headerInfo) {
        String url = joinUrl(gwAddress, path);
        try {
            HttpRequest request = newRequestBuilder(url, headerInfo).GET().build();
            return send(url, request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Mate gateway call interrupted: " + url, e);
        } catch (Exception e) {
            throw logAndWrap(url, e);
        }
    }

    private HttpRequest.Builder newRequestBuilder(String url, RequestHeaderInfo headerInfo) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (headerInfo != null) {
            for (Map.Entry<String, String> header : headerInfo.toHeaders().entrySet()) {
                if (header.getValue() != null && !header.getValue().isEmpty()) {
                    builder.header(header.getKey(), header.getValue());
                }
            }
        }
        return builder;
    }

    private String send(String url, HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.body() == null || response.body().isEmpty()) {
            throw new IllegalStateException("Mate gateway returned empty body");
        }
        return response.body();
    }

    private IllegalStateException logAndWrap(String url, Exception e) {
        log.error("Mate gateway call failed: url={}", url, e);
        return new IllegalStateException("Mate gateway call failed: " + url, e);
    }

    private static String joinUrl(String gwAddress, String path) {
        String base = gwAddress != null && gwAddress.endsWith("/")
                ? gwAddress.substring(0, gwAddress.length() - 1)
                : gwAddress;
        String suffix = path != null && path.startsWith("/") ? path : "/" + path;
        return base + suffix;
    }
}
