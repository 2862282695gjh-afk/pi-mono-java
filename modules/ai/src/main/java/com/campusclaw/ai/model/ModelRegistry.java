/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.ai.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.campusclaw.ai.types.Cost;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.Provider;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.ai.utils.CampusClawHome;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

/**
 * 按 Provider 和模型标识维护模型定义。
 *
 * <p>注册表不提供内置厂商模型；启动时先加载可选的 classpath JSON，再加载用户 JSON。
 * 所有写操作在内部锁上串行化，读取使用并发索引。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class ModelRegistry {
    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private final Object lock = new Object();

    // 两级索引：Provider -> 模型标识 -> 模型。
    private final Map<Provider, Map<String, Model>> models = new ConcurrentHashMap<>();

    /**
     * 按 Provider 和模型标识查询模型。
     *
     * @param provider Provider 标识
     * @param modelId 模型标识
     * @return 已注册模型；未找到时为空
     */
    public Optional<Model> getModel(Provider provider, String modelId) {
        var byId = models.get(provider);
        if (byId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(modelId));
    }

    /**
     * 返回指定 Provider 的全部模型。
     *
     * @param provider Provider 标识
     * @return 不可修改的模型快照
     */
    public List<Model> getModels(Provider provider) {
        var byId = models.get(provider);
        if (byId == null) {
            return List.of();
        }
        synchronized (lock) {
            return List.copyOf(byId.values());
        }
    }

    /**
     * 返回当前包含模型的全部 Provider。
     *
     * @return 不可修改的 Provider 快照
     */
    public List<Provider> getProviders() {
        synchronized (lock) {
            return List.copyOf(models.keySet());
        }
    }

    /**
     * 注册一个模型；相同 Provider 和模型标识的旧值会被替换。
     *
     * @param model 待注册模型
     * @throws NullPointerException 模型为空时抛出
     */
    public void register(Model model) {
        Objects.requireNonNull(model, "model must not be null");
        synchronized (lock) {
            models.computeIfAbsent(model.provider(), key -> new ConcurrentHashMap<>())
                    .put(model.id(), model);
        }
        log.debug(
                "Registered model {} for provider {}",
                model.id(),
                model.provider().value());
    }

    /**
     * 批量注册模型。
     *
     * @param modelList 待注册模型
     * @throws NullPointerException 模型列表为空时抛出
     */
    public void registerAll(List<Model> modelList) {
        Objects.requireNonNull(modelList, "modelList must not be null");
        synchronized (lock) {
            for (var model : modelList) {
                models.computeIfAbsent(model.provider(), key -> new ConcurrentHashMap<>())
                        .put(model.id(), model);
            }
        }
        log.debug("Registered {} model(s)", modelList.size());
    }

    /**
     * 根据模型价格和用量计算费用。
     *
     * @param model 提供价格的模型
     * @param usage Token 用量
     * @return 费用明细
     */
    public static Cost calculateCost(Model model, Usage usage) {
        if (model.cost() == null) {
            return Cost.empty();
        }
        var modelCost = model.cost();
        double input = usage.input() * modelCost.input() / 1_000_000.0;
        double output = usage.output() * modelCost.output() / 1_000_000.0;
        double cacheRead = usage.cacheRead() * modelCost.cacheRead() / 1_000_000.0;
        double cacheWrite = usage.cacheWrite() * modelCost.cacheWrite() / 1_000_000.0;
        return new Cost(input, output, cacheRead, cacheWrite, input + output + cacheRead + cacheWrite);
    }

    /**
     * 判断模型是否支持 xhigh 推理级别。
     *
     * @param model 待检查模型
     * @return 支持时为 true
     */
    public static boolean supportsXhigh(Model model) {
        String id = model.id();
        return id.contains("gpt-5.2")
                || id.contains("gpt-5.3")
                || id.contains("gpt-5.4")
                || id.contains("opus-4-6")
                || id.contains("opus-4.6");
    }

    /**
     * 按 Provider 和模型标识判断两个模型是否相同。
     *
     * @param first 第一个模型，可为空
     * @param second 第二个模型，可为空
     * @return 两个非空模型身份相同时为 true
     */
    public static boolean modelsAreEqual(Model first, Model second) {
        if (first == null || second == null) {
            return false;
        }
        return first.id().equals(second.id()) && first.provider() == second.provider();
    }

    /**
     * 返回所有已注册模型。
     *
     * @return 当前模型快照
     */
    public List<Model> getAllModels() {
        synchronized (lock) {
            var all = new ArrayList<Model>();
            for (var byId : models.values()) {
                all.addAll(byId.values());
            }
            return all;
        }
    }

    /**
     * 清空全部模型。
     */
    public void clear() {
        synchronized (lock) {
            models.clear();
        }
        log.debug("Cleared all models from registry");
    }

    /**
     * 删除指定 Provider 的全部模型。
     *
     * @param provider 待清理的 Provider
     * @throws NullPointerException Provider 为空时抛出
     */
    public void unregisterByProvider(Provider provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        int removed;
        synchronized (lock) {
            var bucket = models.remove(provider);
            removed = bucket == null ? 0 : bucket.size();
        }
        log.debug("Unregistered {} model(s) for provider {}", removed, provider.value());
    }

    /**
     * 启动时依次加载 classpath 和用户模型定义。
     */
    @PostConstruct
    void init() {
        int fromClasspath = loadFromClasspathResource("/campusclaw-models.json");
        int fromUser = loadFromUserFile();
        log.info("ModelRegistry initialized: {} classpath + {} user model(s)", fromClasspath, fromUser);
    }

    /**
     * 从 JSON 文件加载模型；相同身份的旧值会被覆盖。
     *
     * @param file JSON 文件
     * @return 加载的模型数；文件缺失或解析失败时为零
     */
    public int loadFromJsonFile(Path file) {
        if (file == null || !Files.exists(file)) {
            return 0;
        }
        try (var input = Files.newInputStream(file)) {
            return loadFromJsonStream(input, file.toString());
        } catch (IOException | RuntimeException error) {
            log.warn("Failed to load models from {}: {}", file, error.getMessage());
            return 0;
        }
    }

    int loadFromClasspathResource(String resource) {
        try (InputStream input = ModelRegistry.class.getResourceAsStream(resource)) {
            if (input == null) {
                return 0;
            }
            return loadFromJsonStream(input, "classpath:" + resource);
        } catch (IOException | RuntimeException error) {
            log.warn("Failed to load models from {}: {}", resource, error.getMessage());
            return 0;
        }
    }

    private int loadFromUserFile() {
        Path file = CampusClawHome.agentDir().resolve("models.json");
        return loadFromJsonFile(file);
    }

    private int loadFromJsonStream(InputStream input, String source) throws IOException {
        var mapper = new ObjectMapper();
        var loaded = mapper.readValue(input, new TypeReference<List<Model>>() {});
        if (loaded == null || loaded.isEmpty()) {
            return 0;
        }
        registerAll(loaded);
        log.debug("Loaded {} model(s) from {}", loaded.size(), source);
        return loaded.size();
    }
}
