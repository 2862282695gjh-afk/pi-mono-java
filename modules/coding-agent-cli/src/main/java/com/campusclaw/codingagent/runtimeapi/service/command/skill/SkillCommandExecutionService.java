/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.service.command.skill;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.runtime.AgentRuntimeException;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.runtimeapi.dto.command.SkillCommandInputDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventStream;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeV2MessageEventService;
import com.campusclaw.codingagent.runtimeapi.vo.SkillCommandRequestVO;
import com.campusclaw.common.constant.ClawConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 在实际 Agent 快照上展开已绑定 Skill，并复用普通消息的保存、执行与 SSE 生命周期。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class SkillCommandExecutionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillCommandExecutionService.class);

    private final RuntimeV2MessageEventService events;

    public SkillCommandExecutionService(RuntimeV2MessageEventService events) {
        this.events = events;
    }

    /**
     * 将请求原值映射为内部输入，默认值和附件检查仍由统一执行入口负责。
     *
     * @param sessionId Session 标识
     * @param request Skill 请求
     * @param locale 本次语言
     * @param credentials 本次透传凭据
     * @return 普通消息事件流
     * @throws RuntimeApiException 请求缺失或命名空间无效
     */
    public RuntimeEventStream executeRequest(
            String sessionId, SkillCommandRequestVO request, Locale locale, MateCredentials credentials) {
        if (request == null
                || request.getName() == null
                || !request.getName().startsWith(ClawConstants.Skill.COMMAND_PREFIX)) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
        }
        var input = new SkillCommandInputDTO();
        input.setSkillName(request.getName().substring(ClawConstants.Skill.COMMAND_PREFIX.length()));
        input.setArguments(request.getArguments());
        input.setFileIds(request.getFileIds());
        return execute(sessionId, input, locale, credentials);
    }

    public RuntimeEventStream execute(
            String sessionId, SkillCommandInputDTO input, Locale locale, MateCredentials credentials) {
        try {
            SkillCommandInputDTO normalized = normalize(input);
            return events.submitPreparedMessage(
                    sessionId,
                    publicInvocation(normalized),
                    (agentId, runtime) -> expand(agentId, runtime, normalized),
                    normalized.getFileIds(),
                    locale,
                    credentials);
        } catch (RuntimeException error) {
            RuntimeErrorCode code = translate(error);
            LOGGER.error("Skill command failed: errorCode={}", code.name());
            throw new RuntimeApiException(code);
        }
    }

    private static String publicInvocation(SkillCommandInputDTO input) {
        String invocation = "/skill:" + input.getSkillName();
        return input.getArguments().isEmpty() ? invocation : invocation + " " + input.getArguments();
    }

    private static SkillCommandInputDTO normalize(SkillCommandInputDTO input) {
        if (input == null || !ClawConstants.Skill.isValidName(input.getSkillName())) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
        }
        String arguments = input.getArguments();
        List<String> files = input.getFileIds() == null ? List.of() : input.getFileIds();
        if ((arguments != null && arguments.length() > ClawConstants.RuntimeApi.MAX_MESSAGE_CHARACTERS)
                || files.size() > ClawConstants.RuntimeApi.MAX_EVENT_FILE_IDS
                || files.stream()
                        .anyMatch(file -> file == null
                                || !ClawConstants.RuntimeApi.EVENT_FILE_ID_PATTERN
                                        .matcher(file)
                                        .matches())
                || new HashSet<>(files).size() != files.size()) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
        }
        var normalized = new SkillCommandInputDTO();
        normalized.setSkillName(input.getSkillName());
        normalized.setArguments(arguments == null || arguments.isBlank() ? "" : arguments);
        normalized.setFileIds(List.copyOf(files));
        return normalized;
    }

    private static String expand(String agentId, PreparedAgentRuntime runtime, SkillCommandInputDTO input) {
        String content = new SkillCommandAdmission(agentId, input.getSkillName())
                .requireBoundSkill(runtime)
                .content();
        if (content == null || content.isBlank()) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
        }
        String arguments = input.getArguments();
        long length = (long) content.length() + (arguments.isEmpty() ? 0L : 2L + arguments.length());
        if (length > ClawConstants.RuntimeApi.MAX_MESSAGE_CHARACTERS) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
        }
        return arguments.isEmpty() ? content : content + "\n\n" + arguments;
    }

    private static RuntimeErrorCode translate(RuntimeException error) {
        if (error instanceof AgentRuntimeException) {
            return RuntimeErrorCode.AGENT_NOT_AVAILABLE;
        }
        if (!(error instanceof RuntimeApiException api)) {
            return RuntimeErrorCode.COMMAND_EXECUTION_FAILED;
        }
        return switch (api.errorCode()) {
            case INVALID_COMMAND_REQUEST,
                    COMMAND_NOT_FOUND,
                    SESSION_NOT_FOUND,
                    SESSION_BUSY,
                    AGENT_NOT_AVAILABLE,
                    MODEL_NOT_AVAILABLE,
                    THINKING_NOT_SUPPORTED,
                    MANAGER_UNAVAILABLE,
                    RUNTIME_CAPACITY_EXCEEDED -> api.errorCode();
            case AGENT_MODEL_NOT_CONFIGURED -> RuntimeErrorCode.MODEL_NOT_AVAILABLE;
            default -> RuntimeErrorCode.COMMAND_EXECUTION_FAILED;
        };
    }
}
