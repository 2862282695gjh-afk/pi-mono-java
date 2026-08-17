/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.mode.server;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.ai.CampusClawAiService;
import com.huawei.hicampus.mate.matecampusclaw.ai.model.ModelRegistry;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.config.AppPaths;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.config.CustomModelLoader;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.model.ModelCatalogService;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.prompt.SystemPromptBuilder;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.SessionConfig;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.settings.SettingsManager;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.skill.SandboxSkillParser;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.skill.SkillLoader;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.skill.SkillManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerResponse;

/**
 * HTTP 服务模式，启动提供 REST 与 SSE 接口的 Reactor Netty 服务。
 *
 * <p>旧本地接口：
 * <ul>
 *   <li>GET    /api/health                — health check</li>
 *   <li>POST   /api/chat                  — streaming chat (SSE), multi-conversation</li>
 *   <li>DELETE /api/conversations/{id}     — remove a conversation</li>
 *   <li>POST   /api/skills                — upload skill archive</li>
 *   <li>GET    /api/skills                — list installed skills</li>
 *   <li>DELETE /api/skills/{name}         — remove a skill</li>
 *   <li>POST   /api/skills/{name}/enable  — enable a skill</li>
 *   <li>POST   /api/skills/{name}/disable — disable a skill</li>
 *   <li>GET    /api/settings/models       — read defaultModel + customModels + availableModels</li>
 *   <li>PUT    /api/settings/models/default — persist defaultModel to settings.json</li>
 *   <li>PUT    /api/settings/customModels — replace customModels (refreshes ModelRegistry)</li>
 * </ul>
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/05/06]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public class ServerMode {

    private static final Logger log = LoggerFactory.getLogger(ServerMode.class);

    /*
     * 启动横幅专用日志。日志分类名与类包名解耦，可独立于
     * {@code logging.level.com.huawei.hicampus.mate.matecampusclaw} 配置级别；后者保持 WARN 以减少运行噪声。
     * application.yml 将横幅固定为 INFO，使 {@code pi --mode server} 始终向操作者展示接口列表。
     */
    private static final Logger banner = LoggerFactory.getLogger("CampusClawStartupBanner");

    private final CampusClawAiService aiService;
    private final ModelRegistry modelRegistry;
    private final SystemPromptBuilder promptBuilder;
    private final List<AgentTool> tools;
    private final SessionConfig baseConfig;
    private final int port;
    private final String host;
    private final SandboxSkillParser sandboxParser;
    private final boolean useSandbox;
    private final ModelCatalogService modelCatalog;
    private final boolean sessionPersistenceEnabled;
    private final SettingsManager settingsManager;
    private final CustomModelLoader customModelLoader;

    /**
     * 其他模块贡献的附加 {@link RouterFunction}，例如进程内控制面的
     * {@code NodeRoutes} 与 {@code RuntimeRoutes}。
     *
     * <p>这些路由通过 {@link #buildRoutes} 合并到主路由链，使外部处理器复用同一
     * Reactor Netty 服务。
     */
    private List<RouterFunction<ServerResponse>> extraRoutes = List.of();

    public ServerMode(
            CampusClawAiService aiService,
            ModelRegistry modelRegistry,
            SystemPromptBuilder promptBuilder,
            List<AgentTool> tools,
            SessionConfig baseConfig,
            int port) {
        this(
                aiService,
                modelRegistry,
                promptBuilder,
                tools,
                baseConfig,
                port,
                "localhost",
                null,
                false,
                null,
                true,
                null,
                null);
    }

    public ServerMode(
            CampusClawAiService aiService,
            ModelRegistry modelRegistry,
            SystemPromptBuilder promptBuilder,
            List<AgentTool> tools,
            SessionConfig baseConfig,
            int port,
            String host,
            SandboxSkillParser sandboxParser,
            boolean useSandbox,
            ModelCatalogService modelCatalog) {
        this(
                aiService,
                modelRegistry,
                promptBuilder,
                tools,
                baseConfig,
                port,
                host,
                sandboxParser,
                useSandbox,
                modelCatalog,
                true,
                null,
                null);
    }

    public ServerMode(
            CampusClawAiService aiService,
            ModelRegistry modelRegistry,
            SystemPromptBuilder promptBuilder,
            List<AgentTool> tools,
            SessionConfig baseConfig,
            int port,
            String host,
            SandboxSkillParser sandboxParser,
            boolean useSandbox,
            ModelCatalogService modelCatalog,
            boolean sessionPersistenceEnabled) {
        this(
                aiService,
                modelRegistry,
                promptBuilder,
                tools,
                baseConfig,
                port,
                host,
                sandboxParser,
                useSandbox,
                modelCatalog,
                sessionPersistenceEnabled,
                null,
                null);
    }

    public ServerMode(
            CampusClawAiService aiService,
            ModelRegistry modelRegistry,
            SystemPromptBuilder promptBuilder,
            List<AgentTool> tools,
            SessionConfig baseConfig,
            int port,
            String host,
            SandboxSkillParser sandboxParser,
            boolean useSandbox,
            ModelCatalogService modelCatalog,
            boolean sessionPersistenceEnabled,
            SettingsManager settingsManager,
            CustomModelLoader customModelLoader) {
        this.aiService = aiService;
        this.modelRegistry = modelRegistry;
        this.promptBuilder = promptBuilder;
        this.tools = tools;
        this.baseConfig = baseConfig;
        this.port = port;
        this.host = host;
        this.sandboxParser = sandboxParser;
        this.useSandbox = useSandbox;
        this.modelCatalog = modelCatalog;
        this.sessionPersistenceEnabled = sessionPersistenceEnabled;
        this.settingsManager = settingsManager;
        this.customModelLoader = customModelLoader;
    }

    /**
     * 注册由当前 Reactor Netty 实例承载的附加 {@link RouterFunction}。
     *
     * <p>典型来源是 Spring Bean 提供的进程内控制面 {@code NodeRoutes} 与
     * {@code RuntimeRoutes}。路由通过 {@code andOther} 合并，每个函数仍保留自身的
     * {@code RouterFunctions.route()} 结构。
     *
     * @param routes 附加路由函数，允许为 {@code null} 或空集合
     */
    public void setExtraRoutes(List<RouterFunction<ServerResponse>> routes) {
        this.extraRoutes = routes == null ? List.of() : List.copyOf(routes);
    }

    public void run() {
        var sessionPool = new SessionPool(
                aiService,
                modelRegistry,
                promptBuilder,
                tools,
                baseConfig,
                sandboxParser,
                useSandbox,
                sessionPersistenceEnabled);
        var chatHandler = new ChatHandler(sessionPool);
        var skillHandler = new SkillHandler(
                new SkillManager(AppPaths.USER_SKILLS_DIR, sandboxParser, useSandbox),
                new SkillLoader(sandboxParser, useSandbox));
        SettingsHandler settingsHandler = buildSettingsHandler();
        RouterFunction<ServerResponse> routes = buildRoutes(chatHandler, skillHandler, sessionPool, settingsHandler);
        var adapter = new ReactorHttpHandlerAdapter(RouterFunctions.toHttpHandler(routes));
        var server = HttpServer.create()
                .host(host)
                .port(port)
                .route(r -> wireServerRoutes(r, adapter))
                .bindNow();
        logStartupBanner();
        server.onDispose().block();
        sessionPool.shutdown();
    }

    private RouterFunction<ServerResponse> buildRoutes(
            ChatHandler chatHandler,
            SkillHandler skillHandler,
            SessionPool sessionPool,
            SettingsHandler settingsHandler) {
        var conversationLister = new com.huawei.hicampus.mate.matecampusclaw.codingagent.session.ConversationLister();
        var builder = RouterFunctions.route()
                .GET("/api/health", req -> ServerResponse.ok().bodyValue(Map.of("status", "ok")))
                .POST("/api/chat", chatHandler::chat)
                .GET("/api/conversations", req -> ServerResponse.ok()
                        .bodyValue(Map.of(
                                "conversations",
                                com.huawei.hicampus.mate.matecampusclaw.codingagent.session.ConversationLister.toWireFormat(
                                        conversationLister.listForServer()))))
                .DELETE("/api/conversations/{id}", req -> {
                    String id = req.pathVariable("id");
                    boolean removed = sessionPool.remove(id);
                    if (removed) {
                        return ServerResponse.ok().bodyValue(Map.of("message", "Removed conversation: " + id));
                    }
                    return ServerResponse.status(404).bodyValue(Map.of("error", "Conversation not found: " + id));
                })
                .POST("/api/skills", skillHandler::upload)
                .GET("/api/skills", skillHandler::list)
                .DELETE("/api/skills/{name}", skillHandler::delete)
                .POST("/api/skills/{name}/enable", skillHandler::enable)
                .POST("/api/skills/{name}/disable", skillHandler::disable);
        if (settingsHandler != null) {
            builder = builder.GET("/api/settings/models", settingsHandler::getModels)
                    .PUT("/api/settings/models/default", settingsHandler::setDefaultModel)
                    .PUT("/api/settings/customModels", settingsHandler::setCustomModels);
        }
        RouterFunction<ServerResponse> base = builder.build();
        for (RouterFunction<ServerResponse> extra : extraRoutes) {
            base = base.and(extra);
        }
        return base;
    }

    private SettingsHandler buildSettingsHandler() {
        if (settingsManager == null || customModelLoader == null || modelCatalog == null) {
            log.warn(
                    "Settings endpoints disabled: settingsManager / customModelLoader / modelCatalog not wired (server constructed via legacy overload?)");
            return null;
        }
        return new SettingsHandler(settingsManager, modelRegistry, modelCatalog, customModelLoader);
    }

    private static void wireServerRoutes(
            reactor.netty.http.server.HttpServerRoutes routes, ReactorHttpHandlerAdapter adapter) {
        // Vite 开发服务器与 API 不同源，fetch 对非简单请求会先发送 CORS OPTIONS 预检。
        // 返回 204 和允许头，浏览器缓存一小时。
        routes.options("/api/**", (req, res) -> {
            applyCorsHeaders(res);
            return res.status(204).send();
        });
        routes.options("/campusclaw-service/**", (req, res) -> {
            applyCorsHeaders(res);
            return res.status(204).send();
        });

        // 其他路由统一交给 WebFlux RouterFunctions 适配器，并预先写入 CORS 响应头，
        // 使浏览器发出的简单 GET 也能通过同源检查。
        routes.route(req -> true, (req, res) -> {
            applyCorsHeaders(res);
            return adapter.apply(req, res);
        });
    }

    private void logStartupBanner() {
        log.info("CampusClaw API server started on {}:{}", host, port);
        banner.info("CampusClaw API server started on http://{}:{}", host, port);
        banner.info("Endpoints:");
        banner.info("  GET    /api/health");
        banner.info("  POST   /api/chat");
        banner.info("  DELETE /api/conversations/{id}");
        banner.info("  POST   /api/skills");
        banner.info("  GET    /api/skills");
        banner.info("  DELETE /api/skills/{name}");
        banner.info("  POST   /api/skills/{name}/enable");
        banner.info("  POST   /api/skills/{name}/disable");
        if (settingsManager != null && customModelLoader != null && modelCatalog != null) {
            banner.info("  GET    /api/settings/models");
            banner.info("  PUT    /api/settings/models/default");
            banner.info("  PUT    /api/settings/customModels");
        }
        banner.info("  POST   /campusclaw-service/v1/agents/{agent_id}/sessions");
        banner.info("  GET    /campusclaw-service/v1/sessions/{session_id}");
        banner.info("  DELETE /campusclaw-service/v1/sessions/{session_id}");
        banner.info("  POST   /campusclaw-service/v1/sessions/{session_id}/events");
        banner.info("  GET    /campusclaw-service/v1/sessions/{session_id}/events");
        banner.info("  GET    /campusclaw-service/v1/sessions/{session_id}/models");
        banner.info("  PUT    /campusclaw-service/v1/sessions/{session_id}/model");
        banner.info("  PUT    /campusclaw-service/v1/sessions/{session_id}/thinking");
        banner.info("  POST   /campusclaw-service/v1/sessions/{session_id}/steers");
        banner.info("  POST   /campusclaw-service/v1/sessions/{session_id}/follow-ups");
        banner.info("  POST   /campusclaw-service/v1/sessions/{session_id}/abort");
    }

    /**
     * 写入宽松的 CORS 响应头，使不同源的 Vite 开发服务器可以从 JS 调用 REST 接口。
     *
     * <p>服务主要在开发者本机运行，因此 Origin 使用 {@code *}；公开部署时应由运维人员收紧。
     *
     * @param res HTTP 服务响应
     */
    static void applyCorsHeaders(HttpServerResponse res) {
        res.header("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                .header(
                        "Access-Control-Allow-Headers",
                        "Content-Type, Authorization, Accept, Accept-Language, X-HW-ID, X-HW-APPKEY, If-Match")
                .header("Access-Control-Max-Age", "3600");
    }
}
