/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

import com.huawei.hicampus.claw.codingagent.runtime.AgentRuntimeManager;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.claw.codingagent.runtimeapi.result.ResultBeanAdapter;
import com.huawei.hicampus.claw.codingagent.runtimeapi.result.StandaloneResultBeanAdapter;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.BuiltinCommandSource;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.CommandCatalogService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.CommandDiscoveryResponseAssembler;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.CompositeCommandRegistry;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.SessionCompactionApplicationService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.SessionModelConfigurationService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.SessionNamingService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.SessionThinkingConfigurationService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.SkillCommandSource;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.contributor.CompactCommandContributor;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.contributor.HelpCommandContributor;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.contributor.ModelCommandContributor;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.contributor.NameCommandContributor;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.contributor.SkillsCommandContributor;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.contributor.StatusCommandContributor;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.contributor.ThinkingCommandContributor;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.readonly.AgentHelpQueryService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.readonly.BoundSkillQueryService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.readonly.RuntimeSessionStatusService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeSessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 通过真实 MVC 与命令发现链验证共享 GET 的轻量 JSON、状态过滤及操作级错误。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeCommandCatalogRoutesTest {
    private static final String SESSION_ID = "session-0123456789abcdef0123456789abcdef";

    private static final String AGENT_ID = "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static final String ROUTE = "/campusclaw-service/v1/sessions/{sessionId}/commands";

    private final ObjectMapper mapper = new ObjectMapper();

    private final RuntimeSessionDTO session = new RuntimeSessionDTO();

    private AnnotationConfigWebApplicationContext context;

    private RuntimeSessionRepository repository;

    private AgentRuntimeManager manager;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        repository = context.getBean(RuntimeSessionRepository.class);
        manager = context.getBean(AgentRuntimeManager.class);
        session.setId(SESSION_ID);
        session.setAgentId(AGENT_ID);
        session.setState("idle");
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(session));
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void testIdleReturnsExactLightweightFieldsAndStableOrder() throws Exception {
        PreparedAgentRuntime prepared = snapshot(List.of("beta", "alpha"));
        when(manager.prepareCached(AGENT_ID)).thenReturn(prepared);

        MvcResult result = mvc.perform(get(ROUTE, SESSION_ID).header(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "zh-CN"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG))
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andReturn();

        JsonNode body = body(result);
        assertFields(body, "resCode", "resMsg", "result");
        assertThat(body.path("resCode").asText()).isEqualTo("0");
        assertThat(body.path("resMsg").asText()).isEqualTo("success");
        assertFields(body.path("result"), "commands");
        JsonNode commands = body.path("result").path("commands");
        assertThat(names(commands))
                .containsExactly(
                        "help",
                        "status",
                        "name",
                        "model",
                        "thinking",
                        "compact",
                        "skills",
                        "skill:alpha",
                        "skill:beta");
        assertDescriptors(commands, false);
        verify(context.getBean(CompositeCommandRegistry.class)).resolveComplete(session, prepared);
        assertOnlyDiscoveryReads();
    }

    @Test
    void testRunningOmitsCompactSkillsAndMutableInputs() throws Exception {
        session.setState("running");
        when(manager.prepareCached(AGENT_ID)).thenReturn(snapshot(List.of("beta", "alpha")));

        JsonNode commands = body(mvc.perform(get(ROUTE, SESSION_ID))
                        .andExpect(status().isOk())
                        .andReturn())
                .path("result")
                .path("commands");

        assertThat(names(commands)).containsExactly("help", "status", "name", "model", "thinking", "skills");
        assertDescriptors(commands, true);
        assertOnlyDiscoveryReads();
    }

    @ParameterizedTest
    @CsvSource({"idle,7", "running,6"})
    void testCompleteEmptyBindingsRemainSuccessful(String state, int expectedCount) throws Exception {
        session.setState(state);
        when(manager.prepareCached(AGENT_ID)).thenReturn(snapshot(List.of()));

        JsonNode commands = body(mvc.perform(get(ROUTE, SESSION_ID))
                        .andExpect(status().isOk())
                        .andReturn())
                .path("result")
                .path("commands");

        assertThat(commands.isArray()).isTrue();
        assertThat(commands.size()).isEqualTo(expectedCount);
        assertThat(names(commands)).noneMatch(name -> name.startsWith("skill:"));
        assertOnlyDiscoveryReads();
    }

    @ParameterizedTest
    @ValueSource(strings = {"idle", "running"})
    void testMissingCacheFailsTheWholeCatalogWithRetryAfter(String state) throws Exception {
        session.setState(state);

        MvcResult result = mvc.perform(get(ROUTE, SESSION_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "3"))
                .andReturn();

        assertError(result, "AGENT_NOT_AVAILABLE", "The specified Agent is currently unavailable.", "en-US");
        verify(repository).find(SESSION_ID);
        verify(manager).prepareCached(AGENT_ID);
        verifyNoMoreInteractions(repository, manager);
    }

    @Test
    void testIncompleteCacheErrorDoesNotExposeItsCause() throws Exception {
        when(manager.prepareCached(AGENT_ID)).thenThrow(new IllegalArgumentException("private-path-and-body"));

        MvcResult result = mvc.perform(get(ROUTE, SESSION_ID).header(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "3"))
                .andReturn();

        assertError(result, "AGENT_NOT_AVAILABLE", "指定的 Agent 当前不可用。", "zh-CN");
        verify(repository).find(SESSION_ID);
        verify(manager).prepareCached(AGENT_ID);
        verifyNoMoreInteractions(repository, manager);
    }

    @Test
    void testMissingSessionPrecedesCacheReads() throws Exception {
        when(repository.find(SESSION_ID)).thenReturn(Optional.empty());

        MvcResult result = mvc.perform(get(ROUTE, SESSION_ID))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andReturn();

        assertError(result, "SESSION_NOT_FOUND", "The specified Session does not exist.", "en-US");
        verify(repository).find(SESSION_ID);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(manager, context.getBean(CompositeCommandRegistry.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "bad",
                "session-old",
                "SESSION-0123456789abcdef0123456789abcdef",
                "session-gggggggggggggggggggggggggggggggg"
            })
    void testInvalidIdentifierIsRejectedByMvcBeforeApplication(String sessionId) throws Exception {
        MvcResult result = mvc.perform(get(ROUTE, sessionId).header(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN"))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andReturn();

        assertError(result, "INVALID_SESSION_ID", "session_id 格式不正确。", "zh-CN");
        verifyNoInteractions(repository, manager);
    }

    @ParameterizedTest
    @CsvSource({"en-US,en-US", "zh-CN,zh-CN", "fr-FR,en-US", "invalid;q=broken,en-US"})
    void testLanguageNegotiationAndFallback(String requested, String expected) throws Exception {
        when(manager.prepareCached(AGENT_ID)).thenReturn(snapshot(List.of()));

        mvc.perform(get(ROUTE, SESSION_ID).header(HttpHeaders.ACCEPT_LANGUAGE, requested))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, expected))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
        verify(repository).find(SESSION_ID);
        verify(manager).prepareCached(AGENT_ID);
        verifyNoMoreInteractions(repository, manager);
    }

    @Test
    void testOtherPostRetainsAgentUnavailable422WithoutRetryAfter() throws Exception {
        var sessions = context.getBean(RuntimeSessionService.class);
        when(sessions.create(AGENT_ID)).thenThrow(new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_AVAILABLE));

        MvcResult result = mvc.perform(post("/campusclaw-service/v1/agents/{agentId}/sessions", AGENT_ID))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andReturn();

        assertError(result, "AGENT_NOT_AVAILABLE", "The specified Agent is currently unavailable.", "en-US");
        verify(sessions).create(AGENT_ID);
        verifyNoInteractions(repository, manager);
    }

    @Test
    void testSpringRegistersOnlyTheGetCatalogOperation() {
        var mappings = context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getValue().getBeanType() == RuntimeCommandCatalogController.class)
                .toList();

        assertThat(mappings).hasSize(1);
        assertThat(mappings.getFirst().getKey().getPatternValues()).containsExactly(ROUTE);
        assertThat(mappings.getFirst().getKey().getMethodsCondition().getMethods())
                .containsExactly(RequestMethod.GET);
    }

    private void assertDescriptors(JsonNode commands, boolean running) throws Exception {
        Map<String, String> hints = running
                ? Map.of("name", "[displayName]")
                : Map.of("name", "[displayName]", "model", "[modelId]", "thinking", "[on|off]");
        for (JsonNode command : commands) {
            String name = command.path("name").asText();
            boolean skill = name.startsWith("skill:");
            assertThat(command.path("kind").asText()).isEqualTo(skill ? "skill" : "builtin");
            assertThat(command.path("description").asText()).isNotBlank();
            if (skill) {
                assertFields(command, "name", "kind", "description", "input");
                assertThat(command.path("description").asText()).isEqualTo("Description " + name.substring(6));
                assertThat(command.path("input"))
                        .isEqualTo(mapper.readTree("{\"hint\":\"[request]\",\"acceptsFiles\":true}"));
            } else if (hints.containsKey(name)) {
                assertFields(command, "name", "kind", "description", "input");
                assertFields(command.path("input"), "hint");
                assertThat(command.path("input").path("hint").asText()).isEqualTo(hints.get(name));
            } else {
                assertFields(command, "name", "kind", "description");
            }
        }
    }

    private void assertError(MvcResult result, String code, String message, String language) throws Exception {
        assertThat(body(result)).isEqualTo(mapper.valueToTree(Map.of("resCode", code, "resMsg", message)));
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_LANGUAGE)).isEqualTo(language);
        assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static void assertFields(JsonNode node, String... expected) {
        List<String> fields = new ArrayList<>();
        node.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(expected);
    }

    private static List<String> names(JsonNode commands) {
        return StreamSupport.stream(commands.spliterator(), false)
                .map(command -> command.path("name").asText())
                .toList();
    }

    private void assertOnlyDiscoveryReads() {
        verify(repository).find(SESSION_ID);
        verify(manager).prepareCached(AGENT_ID);
        verifyNoMoreInteractions(repository, manager);
    }

    private static PreparedAgentRuntime snapshot(List<String> names) {
        var metadata = new AgentRuntime(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "Agent",
                true,
                AGENT_ID,
                "agent",
                "secret",
                List.of(),
                "private-version");
        List<SkillInfo> skills = names.stream()
                .map(name -> new SkillInfo(
                        name,
                        "private-id",
                        "private-version",
                        "Description " + name,
                        null,
                        "private-body",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()))
                .toList();
        return new PreparedAgentRuntime(AGENT_ID, Path.of("/private-no-read"), metadata, skills);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({
        RuntimeCommandCatalogController.class,
        RuntimeSessionController.class,
        RuntimeExceptionHandler.class,
        RuntimeMessageSourceConfiguration.class,
        CommandCatalogService.class,
        CommandDiscoveryResponseAssembler.class
    })
    static class WebConfiguration {
        @Bean
        RuntimeSessionRepository repository() {
            return mock(RuntimeSessionRepository.class);
        }

        @Bean
        AgentRuntimeManager manager() {
            return mock(AgentRuntimeManager.class);
        }

        @Bean
        RuntimeSessionService sessionService() {
            return mock(RuntimeSessionService.class);
        }

        @Bean
        ResultBeanAdapter resultBeanAdapter() {
            return new StandaloneResultBeanAdapter();
        }

        @Bean
        CompositeCommandRegistry registry(AgentRuntimeManager manager) {
            var builtins = new BuiltinCommandSource(List.of(
                    new HelpCommandContributor(mock(AgentHelpQueryService.class)),
                    new StatusCommandContributor(mock(RuntimeSessionStatusService.class)),
                    new NameCommandContributor(mock(SessionNamingService.class)),
                    new ModelCommandContributor(mock(SessionModelConfigurationService.class)),
                    new ThinkingCommandContributor(mock(SessionThinkingConfigurationService.class)),
                    new CompactCommandContributor(mock(SessionCompactionApplicationService.class)),
                    new SkillsCommandContributor(mock(BoundSkillQueryService.class))));
            return spy(new CompositeCommandRegistry(List.of(builtins, new SkillCommandSource(manager))));
        }
    }
}
