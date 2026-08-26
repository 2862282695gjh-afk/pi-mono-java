/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.common.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.dto.RequestHeaderInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 调用 CampusMate 共享服务地址的 REST 工具。
 *
 * <p>调用方按执行上下文决定是否填充凭据 Header；本类只负责原样映射非空字段，不保存或
 * 解析凭据。传输使用 JDK HttpClient，响应保持原始文本并由调用方解析不同端点的
 * {@code {resCode, resMsg, result}} 信封。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public class MateRestUtil {

    private static final Logger log = LoggerFactory.getLogger(MateRestUtil.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient http;

    /**
     * 使用默认 HTTP 客户端创建实例。
     */
    public MateRestUtil() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    /**
     * 使用指定 HTTP 客户端创建实例，测试可在此注入替身。
     *
     * @param http HTTP 客户端
     */
    public MateRestUtil(HttpClient http) {
        this.http = http;
    }

    /**
     * 向 {@code campusMateBaseUrl + path} 发送 JSON POST 请求并返回原始响应体。
     *
     * @param campusMateBaseUrl CampusMate 服务基础地址
     * @param path CampusMate API 路径
     * @param headerInfo 映射到 HTTP Header 的请求信息
     * @param jsonBody 原始 JSON 请求体
     * @return 原始响应体
     * @throws IllegalStateException 调用失败、中断或返回空响应体时抛出
     */
    public String executePostRawRequest(
            String campusMateBaseUrl, String path, RequestHeaderInfo headerInfo, String jsonBody) {
        String url = joinUrl(campusMateBaseUrl, path);
        try {
            HttpRequest request = newRequestBuilder(url, headerInfo)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();
            return send(url, request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CampusMate call interrupted: " + url, e);
        } catch (Exception e) {
            throw logAndWrap(url, e);
        }
    }

    /**
     * 向 {@code campusMateBaseUrl + path} 发送 GET 请求并返回原始响应体。
     *
     * @param campusMateBaseUrl CampusMate 服务基础地址
     * @param path CampusMate API 路径，可以包含路径变量
     * @param headerInfo 映射到 HTTP Header 的请求信息
     * @return 原始响应体
     * @throws IllegalStateException 调用失败、中断或返回空响应体时抛出
     */
    public String executeGetRawRequest(String campusMateBaseUrl, String path, RequestHeaderInfo headerInfo) {
        String url = joinUrl(campusMateBaseUrl, path);
        try {
            HttpRequest request = newRequestBuilder(url, headerInfo).GET().build();
            return send(url, request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CampusMate call interrupted: " + url, e);
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
            throw new IllegalStateException("CampusMate returned empty body");
        }
        return response.body();
    }

    private IllegalStateException logAndWrap(String url, Exception e) {
        log.error("CampusMate call failed: url={}", url, e);
        return new IllegalStateException("CampusMate call failed: " + url, e);
    }

    private static String joinUrl(String campusMateBaseUrl, String path) {
        String base = campusMateBaseUrl != null && campusMateBaseUrl.endsWith("/")
                ? campusMateBaseUrl.substring(0, campusMateBaseUrl.length() - 1)
                : campusMateBaseUrl;
        String suffix = path != null && path.startsWith("/") ? path : "/" + path;
        return base + suffix;
    }
}
