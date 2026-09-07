/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.service.command;

import com.campusclaw.codingagent.runtimeapi.dto.RuntimeCompactionResultDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.CommandResultDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.HelpCommandResultDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.ModelCommandResultDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.SessionCommandResultDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.SkillsCommandResultDTO;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionResponseAssembler;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionView;
import com.campusclaw.codingagent.runtimeapi.vo.AgentHelpResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.AvailableModelsResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.BoundSkillsResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.CompactionResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.GetSessionResponseVO;

import org.springframework.stereotype.Component;

/**
 * 将内部结果按白名单投影为业务 VO，不按命令名分派、不重新读取资源。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class CommandResponseAssembler {
    private final RuntimeSessionResponseAssembler sessions;

    public CommandResponseAssembler(RuntimeSessionResponseAssembler sessions) {
        this.sessions = sessions;
    }

    public RuntimeSessionView<?> assemble(CommandResultDTO result) {
        return switch (result) {
            case SessionCommandResultDTO value -> session(value);
            case ModelCommandResultDTO value -> new RuntimeSessionView<>(models(value), null);
            case HelpCommandResultDTO value -> new RuntimeSessionView<>(help(value), null);
            case SkillsCommandResultDTO value -> new RuntimeSessionView<>(skills(value), null);
            case RuntimeCompactionResultDTO value ->
                new RuntimeSessionView<>(new CompactionResponseVO(value.compacted()), null);
            default -> throw new IllegalArgumentException("unsupported command result type");
        };
    }

    public RuntimeSessionView<GetSessionResponseVO> session(SessionCommandResultDTO result) {
        return sessions.getView(result.session());
    }

    public AvailableModelsResponseVO models(ModelCommandResultDTO result) {
        return new AvailableModelsResponseVO(result.currentModelId(), result.models());
    }

    public AgentHelpResponseVO help(HelpCommandResultDTO result) {
        return new AgentHelpResponseVO(result.displayName(), result.description(), result.userCases());
    }

    public BoundSkillsResponseVO skills(SkillsCommandResultDTO result) {
        return new BoundSkillsResponseVO(result.skills().stream()
                .map(skill -> new BoundSkillsResponseVO.SkillResponseVO(skill.name(), skill.description()))
                .toList());
    }
}
