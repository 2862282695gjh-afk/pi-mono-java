/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.mode.server;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.campusclaw.ai.model.ModelRegistry;
import com.campusclaw.ai.types.Model;
import com.campusclaw.codingagent.config.CustomModelLoader;
import com.campusclaw.codingagent.model.ModelCatalogService;
import com.campusclaw.codingagent.settings.Settings;
import com.campusclaw.codingagent.settings.SettingsManager;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 处理 UI 读取或修改 {@code ~/.campusclaw/agent/settings.json} 的 HTTP 接口。
 *
 * <p>当前提供三个接口，写操作仅修改全局配置文件：
 *
 * <ul>
 *   <li>GET /api/settings/models：返回当前 {@code defaultModel}、
 *       {@code customModels} 与选择器应展示的可用模型列表。</li>
 *   <li>PUT /api/settings/models/default：设置 {@code defaultModel}，请求体为
 *       {@code {"model": "<id>"}}；模型不在注册表中时返回 400。</li>
 *   <li>PUT /api/settings/customModels：幂等全量替换 {@code customModels} 数组，
 *       并调用 {@link CustomModelLoader#refresh()}，使后续模型列表请求读取到新目录。</li>
 * </ul>
 *
 * <p>配置写入不会修改既有 Agent 实例。新 Session 和后续设置请求可以读取新值，
 * 因为 {@link SettingsManager#load()} 每次调用都会重新读取文件。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/05/22]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public class SettingsHandler {

    private static final Logger log = LoggerFactory.getLogger(SettingsHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Settings.CustomModelConfig>> CUSTOM_MODEL_LIST_TYPE =
            new TypeReference<>() {};

    private final SettingsManager settingsManager;
    private final ModelRegistry modelRegistry;
    private final ModelCatalogService modelCatalog;
    private final CustomModelLoader customModelLoader;

    public SettingsHandler(
            SettingsManager settingsManager,
            ModelRegistry modelRegistry,
            ModelCatalogService modelCatalog,
            CustomModelLoader customModelLoader) {
        this.settingsManager = settingsManager;
        this.modelRegistry = modelRegistry;
        this.modelCatalog = modelCatalog;
        this.customModelLoader = customModelLoader;
    }

    /**
     * 获取配置 UI 使用的模型快照。
     *
     * @param request 服务端请求
     * @return 响应 Mono
     */
    public Mono<ServerResponse> getModels(ServerRequest request) {
        return Mono.fromCallable(this::buildModelsSnapshot)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(snapshot -> ServerResponse.ok().bodyValue(snapshot))
                .onErrorResume(Exception.class, e -> {
                    log.error("Failed to read settings snapshot", e);
                    return ServerResponse.status(500).bodyValue(Map.of("error", "Internal error: " + e.getMessage()));
                });
    }

    /**
     * 设置默认模型，请求体为 {@code {"model": "<id>"}}。
     *
     * @param request 服务端请求
     * @return 响应 Mono
     */
    public Mono<ServerResponse> setDefaultModel(ServerRequest request) {
        return request.bodyToMono(DefaultModelRequest.class)
                .defaultIfEmpty(new DefaultModelRequest(null))
                .flatMap(
                        req -> Mono.fromCallable(() -> applyDefaultModel(req)).subscribeOn(Schedulers.boundedElastic()))
                .flatMap(this::toResponse)
                .onErrorResume(Exception.class, e -> {
                    log.error("Failed to update defaultModel", e);
                    return ServerResponse.status(500).bodyValue(Map.of("error", "Internal error: " + e.getMessage()));
                });
    }

    /**
     * 全量替换自定义模型，请求体是 {@link Settings.CustomModelConfig} 的 JSON 数组。
     *
     * @param request 服务端请求
     * @return 响应 Mono
     */
    public Mono<ServerResponse> setCustomModels(ServerRequest request) {
        return request.bodyToMono(JsonNode.class)
                .defaultIfEmpty(MAPPER.createArrayNode())
                .flatMap(body ->
                        Mono.fromCallable(() -> applyCustomModels(body)).subscribeOn(Schedulers.boundedElastic()))
                .flatMap(this::toResponse)
                .onErrorResume(Exception.class, e -> {
                    log.error("Failed to update customModels", e);
                    return ServerResponse.status(500).bodyValue(Map.of("error", "Internal error: " + e.getMessage()));
                });
    }

    Map<String, Object> buildModelsSnapshot() {
        Settings settings = settingsManager.load();
        List<Model> available = modelCatalog.getAvailableModels();
        var entries = new ArrayList<Map<String, Object>>(available.size());
        for (Model m : available) {
            entries.add(modelToWireFormat(m, modelCatalog.hasCredentials(m)));
        }

        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("defaultModel", settings.resolvedDefaultModel());
        snapshot.put("customModels", settings.customModels() == null ? List.of() : settings.customModels());
        snapshot.put("availableModels", entries);
        snapshot.put("filtered", modelCatalog.isFiltered());
        return snapshot;
    }

    ApiResult applyDefaultModel(DefaultModelRequest req) {
        if (req == null || req.model() == null || req.model().isBlank()) {
            return ApiResult.badRequest("model is required");
        }
        String requested = req.model();
        if (!modelIdExists(requested)) {
            return ApiResult.badRequest("unknown model: " + requested);
        }
        settingsManager.setGlobal("defaultModel", requested);
        return ApiResult.ok(Map.of("defaultModel", requested));
    }

    ApiResult applyCustomModels(JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) {
            return ApiResult.badRequest("request body must be a JSON array of customModels");
        }
        if (!body.isArray()) {
            return ApiResult.badRequest("customModels payload must be a JSON array");
        }

        List<Settings.CustomModelConfig> parsed;
        try {
            parsed = MAPPER.convertValue(body, CUSTOM_MODEL_LIST_TYPE);
        } catch (IllegalArgumentException e) {
            return ApiResult.badRequest("malformed customModels payload: " + e.getMessage());
        }
        if (parsed == null) {
            parsed = List.of();
        }

        String validationError = validateCustomModels(parsed);
        if (validationError != null) {
            return ApiResult.badRequest(validationError);
        }

        settingsManager.setGlobal("customModels", parsed);
        customModelLoader.refresh();
        return ApiResult.ok(Map.of("customModels", parsed, "count", parsed.size()));
    }

    @Nullable
    private static String validateCustomModels(List<Settings.CustomModelConfig> list) {
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            Settings.CustomModelConfig cfg = list.get(i);
            if (cfg == null) {
                return "customModels[" + i + "] is null";
            }
            if (cfg.id() == null || cfg.id().isBlank()) {
                return "customModels[" + i + "].id is required";
            }
            if (cfg.api() == null || cfg.api().isBlank()) {
                return "customModels[" + i + "].api is required (id=" + cfg.id() + ")";
            }
            if (!seenIds.add(cfg.id())) {
                return "duplicate customModels id: " + cfg.id();
            }
        }
        return null;
    }

    private boolean modelIdExists(String modelId) {
        for (Model m : modelRegistry.getAllModels()) {
            if (m.id().equals(modelId)) {
                return true;
            }
        }
        return false;
    }

    private Mono<ServerResponse> toResponse(ApiResult result) {
        if (result.errorMessage != null) {
            return ServerResponse.status(result.status).bodyValue(Map.of("error", result.errorMessage));
        }
        return ServerResponse.ok().bodyValue(result.body);
    }

    private static Map<String, Object> modelToWireFormat(Model m, boolean hasCredentials) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("id", m.id());
        entry.put("name", m.name());
        entry.put("provider", m.provider().value());
        entry.put("contextWindow", m.contextWindow());
        entry.put("maxTokens", m.maxTokens());
        entry.put("reasoning", m.reasoning());
        entry.put("hasCredentials", hasCredentials);
        if (m.cost() != null) {
            var cost = new LinkedHashMap<String, Object>();
            cost.put("input", m.cost().input());
            cost.put("output", m.cost().output());
            cost.put("cacheRead", m.cost().cacheRead());
            cost.put("cacheWrite", m.cost().cacheWrite());
            entry.put("cost", cost);
        }
        return entry;
    }

    /**
     * {@code PUT /api/settings/models/default} 的请求体。
     */
    public record DefaultModelRequest(@JsonProperty("model") @Nullable String model) {}

    static final class ApiResult {
        final int status;

        @Nullable
        final String errorMessage;

        @Nullable
        final Map<String, Object> body;

        private ApiResult(int status, @Nullable String errorMessage, @Nullable Map<String, Object> body) {
            this.status = status;
            this.errorMessage = errorMessage;
            this.body = body;
        }

        static ApiResult ok(Map<String, Object> body) {
            return new ApiResult(200, null, body);
        }

        static ApiResult badRequest(String message) {
            return new ApiResult(400, message, null);
        }
    }
}
