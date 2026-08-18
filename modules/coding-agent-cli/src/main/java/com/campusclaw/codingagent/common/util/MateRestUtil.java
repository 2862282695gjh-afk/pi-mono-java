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

import com.campusclaw.codingagent.common.dto.RequestHeaderInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST helper for calls to the Mate inner gateway ({@code mate.innerGWSerive}).
 *
 * <p>Canonical call shape (as used by the Mate tool client):
 * <pre>
 * ResultInfoPair&lt;T&gt; resultInfoPair =
 *         mateRestUtil.executePostJsonRequest(gwAddress, path, headerInfo, requestBody, clazz);
 * </pre>
 *
 * <p>The internal gateway does not require credential headers; a default
 * {@code RequestHeaderInfo.builder().build()} is sufficient. The default
 * implementation posts JSON via the JDK HttpClient and unwraps the standard
 * {@code {resCode, resMsg, result}} envelope into {@link ResultInfoPair},
 * decoding {@code result.data} into the requested type. The internal
 * development may replace the transport with the campuscommon RestUtil while
 * keeping this signature.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public class MateRestUtil {

    private static final Logger log = LoggerFactory.getLogger(MateRestUtil.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final ObjectMapper mapper;

    private final HttpClient http;

    /**
     * Creates a MateRestUtil with a default HTTP client.
     *
     * @param mapper Jackson mapper used for request/response serialization
     */
    public MateRestUtil(ObjectMapper mapper) {
        this(mapper, HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    /**
     * Creates a MateRestUtil with the given HTTP client (tests inject mocks here).
     *
     * @param mapper Jackson mapper used for request/response serialization
     * @param http the HTTP client to use
     */
    public MateRestUtil(ObjectMapper mapper, HttpClient http) {
        this.mapper = mapper;
        this.http = http;
    }

    /**
     * POSTs a JSON body to {@code gwAddress + path} and unwraps the standard
     * result envelope.
     *
     * @param <T> expected type of {@code resultData}
     * @param gwAddress Mate inner gateway base address, e.g. {@code mate.innerGWSerive}
     * @param path API path on the gateway, e.g. {@code MATETOOLSERVERCALLTOOL}
     * @param headerInfo request header info (credentials not required)
     * @param requestBody request body object; serialized as JSON
     * @param responseType class of the expected {@code resultData}
     * @return result envelope: code, description and decoded data
     * @throws IllegalStateException when the call fails, is interrupted, or
     *         returns an unparseable body
     */
    public <T> ResultInfoPair<T> executePostJsonRequest(
            String gwAddress, String path, RequestHeaderInfo headerInfo, Object requestBody, Class<T> responseType) {
        String url = joinUrl(gwAddress, path);
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(requestBody), StandardCharsets.UTF_8));
            if (headerInfo != null) {
                log.debug("Mate gateway call with header info: {}", headerInfo);
            }
            HttpResponse<String> response =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return decodeEnvelope(response.body(), responseType);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Mate gateway call interrupted: " + url, e);
        } catch (Exception e) {
            log.error("Mate gateway call failed: url={}", url, e);
            throw new IllegalStateException("Mate gateway call failed: " + url, e);
        }
    }

    /**
     * POSTs a raw JSON body to {@code gwAddress + path} and returns the raw
     * response body. For callers that decode collections (e.g.
     * {@code List<ToolInfo>}) which a {@code Class<T>} cannot express.
     *
     * @param gwAddress Mate inner gateway base address
     * @param path API path on the gateway
     * @param headerInfo request header info (credentials not required)
     * @param jsonBody raw JSON request body
     * @return raw response body string
     * @throws IllegalStateException when the call fails, is interrupted, or
     *         returns an empty body
     */
    public String executePostRawRequest(String gwAddress, String path, RequestHeaderInfo headerInfo, String jsonBody) {
        String url = joinUrl(gwAddress, path);
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            HttpResponse<String> response =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.body() == null || response.body().isEmpty()) {
                throw new IllegalStateException("Mate gateway returned empty body");
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Mate gateway call interrupted: " + url, e);
        } catch (Exception e) {
            log.error("Mate gateway call failed: url={}", url, e);
            throw new IllegalStateException("Mate gateway call failed: " + url, e);
        }
    }

    private <T> ResultInfoPair<T> decodeEnvelope(String body, Class<T> responseType) throws Exception {
        if (body == null || body.isEmpty()) {
            throw new IllegalStateException("Mate gateway returned empty body");
        }
        var root = mapper.readTree(body);
        String code = root.path("resCode").asText("");
        String msg = root.path("resMsg").asText("");
        T data = null;
        JsonNode resultNode = root.path("result");
        JsonNode dataNode = resultNode.path("data");
        if (dataNode.isArray() || dataNode.isObject()) {
            data = mapper.treeToValue(dataNode, responseType);
        } else if (resultNode.isObject()) {
            data = mapper.treeToValue(resultNode, responseType);
        }
        return new ResultInfoPair<>(code, msg, data);
    }

    private static String joinUrl(String gwAddress, String path) {
        String base = gwAddress != null && gwAddress.endsWith("/")
                ? gwAddress.substring(0, gwAddress.length() - 1)
                : gwAddress;
        String suffix = path != null && path.startsWith("/") ? path : "/" + path;
        return base + suffix;
    }

    /**
     * Result envelope returned by Mate gateway APIs: {@code resCode},
     * {@code resMsg} and the typed {@code result.data}.
     *
     * @param resultCode gateway result code; "0" typically means success
     * @param resultMsg human-readable result message
     * @param resultData decoded payload of type T
     * @param <T> payload type
     * @version [br_eCampusCore 26.0.0, 2026/08/18]
     * @since [br_eCampusCore 26.0.0]
     */
    public record ResultInfoPair<T>(String resultCode, String resultMsg, T resultData) {

        /**
         * Returns whether the gateway reported success.
         *
         * @return true when resultCode is "0"
         */
        public boolean isSuccess() {
            return "0".equals(resultCode);
        }
    }
}
