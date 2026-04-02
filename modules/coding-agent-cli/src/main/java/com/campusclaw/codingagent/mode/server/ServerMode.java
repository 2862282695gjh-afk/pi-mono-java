package com.campusclaw.codingagent.mode.server;

import java.util.Map;

import com.campusclaw.codingagent.config.AppPaths;
import com.campusclaw.codingagent.session.AgentSession;
import com.campusclaw.codingagent.skill.SkillLoader;
import com.campusclaw.codingagent.skill.SkillManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import reactor.netty.http.server.HttpServer;

/**
 * HTTP server mode: starts a Reactor Netty server exposing REST + SSE endpoints.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET    /api/health         — health check</li>
 *   <li>POST   /api/chat           — streaming chat (SSE)</li>
 *   <li>POST   /api/skills         — upload skill archive</li>
 *   <li>GET    /api/skills         — list installed skills</li>
 *   <li>DELETE /api/skills/{name}  — remove a skill</li>
 * </ul>
 */
public class ServerMode {

    private static final Logger log = LoggerFactory.getLogger(ServerMode.class);

    private final AgentSession session;
    private final int port;

    public ServerMode(AgentSession session, int port) {
        this.session = session;
        this.port = port;
    }

    public void run() {
        var chatHandler = new ChatHandler(session);
        var skillHandler = new SkillHandler(
                new SkillManager(AppPaths.USER_SKILLS_DIR),
                new SkillLoader());

        RouterFunction<ServerResponse> routes = RouterFunctions.route()
                .GET("/api/health", req ->
                        ServerResponse.ok().bodyValue(Map.of("status", "ok")))
                .POST("/api/chat", chatHandler::chat)
                .POST("/api/skills", skillHandler::upload)
                .GET("/api/skills", skillHandler::list)
                .DELETE("/api/skills/{name}", skillHandler::delete)
                .build();

        var httpHandler = RouterFunctions.toHttpHandler(routes);
        var adapter = new ReactorHttpHandlerAdapter(httpHandler);

        var server = HttpServer.create()
                .port(port)
                .handle(adapter)
                .bindNow();

        log.info("CampusClaw API server started on port {}", port);
        System.out.println("CampusClaw API server started on http://localhost:" + port);
        System.out.println("Endpoints:");
        System.out.println("  GET    /api/health");
        System.out.println("  POST   /api/chat");
        System.out.println("  POST   /api/skills");
        System.out.println("  GET    /api/skills");
        System.out.println("  DELETE /api/skills/{name}");

        server.onDispose().block();
    }
}
