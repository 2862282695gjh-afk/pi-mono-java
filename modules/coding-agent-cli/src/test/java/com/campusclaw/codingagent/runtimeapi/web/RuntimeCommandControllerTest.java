/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.runtime.AgentRuntimeManager;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.runtimeapi.compaction.RuntimeCompactionCall;
import com.campusclaw.codingagent.runtimeapi.compaction.RuntimeCompactionService;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeCompactionResultDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.ModelCommandResultDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.SessionCommandResultDTO;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventService;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventStream;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeSseDispatcher;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.result.StandaloneResultBeanAdapter;
import com.campusclaw.codingagent.runtimeapi.service.command.BuiltinCommandSource;
import com.campusclaw.codingagent.runtimeapi.service.command.CommandExecutionService;
import com.campusclaw.codingagent.runtimeapi.service.command.CommandResponseAssembler;
import com.campusclaw.codingagent.runtimeapi.service.command.CompositeCommandRegistry;
import com.campusclaw.codingagent.runtimeapi.service.command.SessionCompactionApplicationService;
import com.campusclaw.codingagent.runtimeapi.service.command.SessionModelConfigurationService;
import com.campusclaw.codingagent.runtimeapi.service.command.SessionNamingService;
import com.campusclaw.codingagent.runtimeapi.service.command.SessionThinkingConfigurationService;
import com.campusclaw.codingagent.runtimeapi.service.command.contributor.CompactCommandContributor;
import com.campusclaw.codingagent.runtimeapi.service.command.contributor.HelpCommandContributor;
import com.campusclaw.codingagent.runtimeapi.service.command.contributor.ModelCommandContributor;
import com.campusclaw.codingagent.runtimeapi.service.command.contributor.NameCommandContributor;
import com.campusclaw.codingagent.runtimeapi.service.command.contributor.SkillsCommandContributor;
import com.campusclaw.codingagent.runtimeapi.service.command.contributor.StatusCommandContributor;
import com.campusclaw.codingagent.runtimeapi.service.command.contributor.ThinkingCommandContributor;
import com.campusclaw.codingagent.runtimeapi.service.command.readonly.AgentHelpQueryService;
import com.campusclaw.codingagent.runtimeapi.service.command.readonly.BoundSkillQueryService;
import com.campusclaw.codingagent.runtimeapi.service.command.readonly.RuntimeSessionStatusService;
import com.campusclaw.codingagent.runtimeapi.service.command.skill.SkillCommandExecutionService;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionResponseAssembler;
import com.campusclaw.codingagent.runtimeapi.session.SessionEtagFactory;
import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.campusclaw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class RuntimeCommandControllerTest {
    private static final String SESSION_ID = "session-0123456789abcdef0123456789abcdef";

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    private final LocalValidatorFactoryBean validation = new LocalValidatorFactoryBean();

    private final RuntimeSseDispatcher dispatcher = new RuntimeSseDispatcher();

    private final RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);

    private final AgentRuntimeManager agents = mock(AgentRuntimeManager.class);

    private final RuntimeEventService events = mock(RuntimeEventService.class);

    private final RuntimeCompactionService runtime = mock(RuntimeCompactionService.class);

    private final MateCredentials credentials = MateCredentials.appKey("caller", "private-key", "private-token");

    private MockMvc mvc;

    @BeforeEach
    void prepare() {
        validation.afterPropertiesSet();
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(session()));
        when(agents.prepareCached("agent")).thenReturn(prepared());
        var compaction = new SessionCompactionApplicationService(runtime);
        var call = mock(RuntimeCompactionCall.class);
        when(call.result()).thenReturn(CompletableFuture.completedStage(new RuntimeCompactionResultDTO(false, null)));
        when(runtime.start(SESSION_ID, credentials, Locale.CHINA)).thenReturn(call);
        var builtins = new CommandExecutionService(
                repository,
                new CompositeCommandRegistry(List.of(contributors(compaction))),
                compaction,
                new CommandResponseAssembler(new RuntimeSessionResponseAssembler(new SessionEtagFactory())),
                validation);
        mvc = MockMvcBuilders.standaloneSetup(new RuntimeCommandController(
                        builtins,
                        new SkillCommandExecutionService(events),
                        new StandaloneResultBeanAdapter(),
                        dispatcher))
                .setValidator(validation)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    @AfterEach
    void close() {
        dispatcher.close();
        validation.close();
    }

    @ParameterizedTest
    @MethodSource("builtinRequests")
    void shouldReturnExactJsonForAllSevenBuiltinsRegardlessOfAccept(String name, String accept) throws Exception {
        var response = mvc.perform(command("{\"name\":\"" + name + "\"}").header(HttpHeaders.ACCEPT, accept))
                .andExpect(status().isOk())
                .andExpect(request().asyncNotStarted())
                .andReturn()
                .getResponse();

        JsonNode envelope = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(envelope.properties()).extracting(Map.Entry::getKey).containsExactly("resCode", "resMsg", "result");
        assertThat(envelope.path("resCode").asText()).isEqualTo("0");
        assertThat(envelope.path("resMsg").asText()).isEqualTo("success");
        assertThat(envelope.path("result")).isEqualTo(expected(name));
        assertHeaders(response, MediaType.APPLICATION_JSON_VALUE);
        boolean sessionResult = List.of("status", "name", "thinking").contains(name);
        assertThat(response.getHeader(HttpHeaders.ETAG))
                .isEqualTo(sessionResult ? new SessionEtagFactory().create(SESSION_ID, 3L) : null);
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @CsvSource({"name,新名称", "model,other-model", "thinking,on"})
    void shouldReturnCommittedSessionAndEtagForChanges(String name, String arguments) throws Exception {
        var response = mvc.perform(command(json.writeValueAsString(Map.of("name", name, "arguments", arguments)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(request().asyncNotStarted())
                .andReturn()
                .getResponse();

        var expected = (ObjectNode) expected("status");
        switch (name) {
            case "name" -> expected.put("displayName", arguments);
            case "model" -> expected.put("modelId", arguments);
            default -> expected.put("thinking", true);
        }
        assertThat(json.readTree(response.getContentAsString(StandardCharsets.UTF_8))
                        .path("result"))
                .isEqualTo(expected);
        assertThat(response.getHeader(HttpHeaders.ETAG)).isEqualTo(new SessionEtagFactory().create(SESSION_ID, 4L));
        assertHeaders(response, MediaType.APPLICATION_JSON_VALUE);
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/event-stream", "application/json, text/event-stream"})
    void shouldExecuteNameOnlySkillThroughRealExpansionAndSse(String accept) throws Exception {
        var stream = spy(new RuntimeEventStream(16, 65536L, Duration.ofMinutes(1), event -> 256L));
        when(events.submitPreparedMessage(anyString(), any(), any(), any(), any()))
                .thenAnswer(call -> {
                    BiFunction<String, PreparedAgentRuntime, String> prepare = call.getArgument(1);
                    String message = prepare.apply("agent", prepared());
                    assertThat(message).isEqualTo("完整 Skill 正文");
                    assertThat(stream.emit(new RuntimeSseEventVO("1", "message.end", Map.of("message", message))))
                            .isTrue();
                    return stream;
                });
        var result = mvc.perform(command("{\"name\":\"skill:pdf\"}").header(HttpHeaders.ACCEPT, accept))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        stream.complete();
        result.getAsyncResult(5000L);
        var asyncContext = result.getRequest().getAsyncContext();
        var response = mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        asyncContext.complete();

        assertHeaders(response, MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(response.getHeader(HttpHeaders.ETAG)).isNull();
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("id:1\n", "event:message.end\n", "data:{\"message\":\"完整 Skill 正文\"}\n")
                .endsWith("\n\n")
                .doesNotContain("resCode", "private", "command.started");
        verify(events).submitPreparedMessage(eq(SESSION_ID), any(), eq(List.of()), eq(Locale.CHINA), eq(credentials));
        verify(stream, atLeastOnce()).detach();
        verifyNoInteractions(repository, agents, runtime);
    }

    private BuiltinCommandSource contributors(SessionCompactionApplicationService compaction) {
        var name = mock(SessionNamingService.class);
        var model = mock(SessionModelConfigurationService.class);
        var thinking = mock(SessionThinkingConfigurationService.class);
        var current = new SessionCommandResultDTO(session(), false, null);
        when(name.execute(SESSION_ID, "")).thenReturn(current);
        when(name.execute(SESSION_ID, "新名称")).thenReturn(committed("新名称", "model", false));
        when(thinking.execute(SESSION_ID, "")).thenReturn(current);
        when(thinking.execute(SESSION_ID, "on")).thenReturn(committed(null, "model", true));
        when(model.execute(SESSION_ID, "")).thenReturn(new ModelCommandResultDTO("model", List.of("model")));
        when(model.execute(SESSION_ID, "other-model")).thenReturn(committed(null, "other-model", false));
        return new BuiltinCommandSource(List.of(
                new HelpCommandContributor(new AgentHelpQueryService(agents)),
                new StatusCommandContributor(new RuntimeSessionStatusService(repository)),
                new NameCommandContributor(name),
                new ModelCommandContributor(model),
                new ThinkingCommandContributor(thinking),
                new CompactCommandContributor(compaction),
                new SkillsCommandContributor(new BoundSkillQueryService(agents))));
    }

    private static SessionCommandResultDTO committed(String displayName, String modelId, boolean thinking) {
        var changed = session();
        changed.setDisplayName(displayName);
        changed.setModelId(modelId);
        changed.setThinking(thinking);
        changed.setResourceVersion(4L);
        return new SessionCommandResultDTO(changed, true, 20L);
    }

    private static Stream<Arguments> builtinRequests() {
        return Stream.of("help", "status", "name", "model", "thinking", "compact", "skills")
                .flatMap(name -> Stream.of(
                                "application/json", "text/event-stream", "application/json, text/event-stream")
                        .map(accept -> Arguments.of(name, accept)));
    }

    private JsonNode expected(String name) throws Exception {
        return json.readTree(
                switch (name) {
                    case "help" -> "{\"displayName\":\"Agent\",\"description\":[\"介绍\"],\"userCases\":[\"场景\"]}";
                    case "model" -> "{\"currentModelId\":\"model\",\"models\":[\"model\"]}";
                    case "compact" -> "{\"compacted\":false}";
                    case "skills" -> "{\"skills\":[{\"name\":\"pdf\",\"description\":\"description\"}]}";
                    default ->
                        """
                    {"sessionId":"%s","agentId":"agent","displayName":null,"modelId":"model",
                     "state":"idle","thinking":false,"lifetimeUsage":{"input":0,"output":0,"cacheRead":0,
                      "cacheWrite":0,"totalTokens":0,"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"total":0}},
                     "createdAt":"2026-09-01T00:00:00Z","updatedAt":"2026-09-01T00:00:00Z"}
                    """
                                .formatted(SESSION_ID);
                });
    }

    private static MockHttpServletRequestBuilder command(String body) {
        return post(ClawConstants.RuntimeApi.BASE_PATH + "/sessions/" + SESSION_ID + "/command")
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .header(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN")
                .header("X-HW-ID", "caller")
                .header("X-HW-APPKEY", "private-key")
                .header("access-token", "private-token")
                .content(body);
    }

    private static void assertHeaders(MockHttpServletResponse response, String contentType) {
        assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.valueOf(contentType)))
                .isTrue();
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader(HttpHeaders.CONTENT_LANGUAGE)).isEqualTo("zh-CN");
    }

    private static RuntimeSessionDTO session() {
        var value = new RuntimeSessionDTO();
        value.setId(SESSION_ID);
        value.setAgentId("agent");
        value.setState("idle");
        value.setModelId("model");
        value.setResourceVersion(3L);
        value.setCreatedAt(OffsetDateTime.parse("2026-09-01T00:00:00Z"));
        value.setUpdatedAt(value.getCreatedAt());
        return value;
    }

    private static PreparedAgentRuntime prepared() {
        var metadata = new AgentRuntime(
                List.of("model"),
                null,
                null,
                null,
                List.of("介绍"),
                "Agent",
                true,
                "agent",
                "agent",
                "system",
                List.of("场景"),
                "v2");
        var skill = new SkillInfo("pdf", "skill-1", "v2", "description", null, "完整 Skill 正文", null, null, null, null);
        return new PreparedAgentRuntime("agent", Path.of("/unread-private-path"), metadata, List.of(skill));
    }
}
