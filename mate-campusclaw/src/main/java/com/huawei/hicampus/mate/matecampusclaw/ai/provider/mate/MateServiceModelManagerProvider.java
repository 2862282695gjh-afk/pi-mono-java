/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.ai.provider.mate;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.huawei.hicampus.mate.matecampusclaw.ai.provider.AiProvider;
import com.huawei.hicampus.mate.matecampusclaw.ai.provider.ProviderAuth;
import com.huawei.hicampus.mate.matecampusclaw.ai.provider.ProviderId;
import com.huawei.hicampus.mate.matecampusclaw.ai.stream.AssistantMessageEventStream;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Api;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.AssistantMessage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Context;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.SimpleStreamOptions;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.StopReason;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

/**
 * 通过内部网关免认证调用 CampusMate Chat 接口的通用 Provider。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/25]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class MateServiceModelManagerProvider implements AiProvider {
    public static final ProviderId PROVIDER_ID = new ProviderId("mate-model-manager");

    private static final String SUPPORTED_API = "openai-completions";

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final ObjectMapper mapper;

    private final MateChatRequestMapper requestMapper;

    private final WebClient webClient;

    private final URI endpoint;

    private final String api;

    public MateServiceModelManagerProvider(
            ObjectMapper mapper,
            @Value("${campusmate.model-manager.base-url:https://localhost:8591}") String baseUrl,
            @Value("${campusmate.model-manager.chat-path:/mate-service/v1/LLM/chat}") String chatPath,
            @Value("${campusmate.model-manager.api:openai-completions}") String api,
            @Value("${campusmate.model-manager.connect-timeout:PT10S}") Duration connectTimeout,
            @Value("${campusmate.model-manager.response-timeout:PT10M}") Duration responseTimeout) {
        this(mapper, createWebClient(connectTimeout, responseTimeout), endpoint(baseUrl, chatPath), api);
    }

    MateServiceModelManagerProvider(ObjectMapper mapper, WebClient webClient, URI endpoint, String api) {
        this.mapper = mapper;
        this.requestMapper = new MateChatRequestMapper(mapper);
        this.webClient = webClient;
        this.endpoint = endpoint;
        this.api = api;
    }

    @PostConstruct
    void validateConfiguration() {
        if (!SUPPORTED_API.equals(api)) {
            throw new IllegalStateException("Unsupported campusmate.model-manager.api: " + api);
        }
    }

    @Override
    public ProviderId id() {
        return PROVIDER_ID;
    }

    @Override
    public ProviderAuth auth() {
        return ProviderAuth.none();
    }

    @Override
    public AssistantMessageEventStream streamSimple(
            Model model, Context context, @Nullable SimpleStreamOptions options) {
        AssistantMessageEventStream stream = new AssistantMessageEventStream();
        JsonNode request;
        try {
            request = requestMapper.map(model, context, options);
        } catch (RuntimeException error) {
            stream.pushError("error", errorMessage(model, error));
            return stream;
        }
        subscribe(model, request, stream);
        return stream;
    }

    private void subscribe(Model model, JsonNode request, AssistantMessageEventStream stream) {
        MateChatSseParser parser = new MateChatSseParser(mapper, model, stream);
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        Disposable disposable = eventFlux(request).subscribe(parser::accept, parser::fail, parser::completeWithoutDone);
        subscription.set(disposable);
        stream.onCancel(() -> cancel(subscription, parser));
    }

    private Flux<ServerSentEvent<String>> eventFlux(JsonNode request) {
        return webClient
                .post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .exchangeToFlux(this::decodeResponse);
    }

    private Flux<ServerSentEvent<String>> decodeResponse(ClientResponse response) {
        if (response.statusCode().is2xxSuccessful()) {
            MediaType contentType = response.headers().contentType().orElse(null);
            if (contentType == null || !MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) {
                return Flux.error(new MateModelInvocationException(
                        "INVALID_MATE_RESPONSE", "Mate Chat did not return text/event-stream"));
            }
            return response.bodyToFlux(SSE_TYPE);
        }
        return response.bodyToMono(JsonNode.class)
                .defaultIfEmpty(mapper.createObjectNode())
                .flatMapMany(body -> Flux.error(httpError(response, body)));
    }

    private static MateModelInvocationException httpError(ClientResponse response, JsonNode body) {
        String code = body.path("resCode").asText("MATE_MODEL_MANAGER_ERROR");
        String message = body.path("resMsg")
                .asText("Mate Model Manager returned HTTP "
                        + response.statusCode().value());
        return new MateModelInvocationException(code, message);
    }

    private static void cancel(AtomicReference<Disposable> subscription, MateChatSseParser parser) {
        Disposable disposable = subscription.get();
        if (disposable != null) {
            disposable.dispose();
        }
        parser.abort();
    }

    private static WebClient createWebClient(Duration connectTimeout, Duration responseTimeout) {
        HttpClient client = HttpClient.create()
                .disableRetry(true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis()))
                .responseTimeout(responseTimeout);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(client))
                .build();
    }

    private static URI endpoint(String baseUrl, String chatPath) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String path = chatPath.startsWith("/") ? chatPath : "/" + chatPath;
        return URI.create(base + path);
    }

    private static AssistantMessage errorMessage(Model model, Throwable error) {
        return new AssistantMessage(
                List.of(),
                Api.OPENAI_COMPLETIONS.value(),
                model.provider().value(),
                model.id(),
                null,
                null,
                Usage.empty(),
                StopReason.ERROR,
                error.getMessage(),
                System.currentTimeMillis());
    }
}
