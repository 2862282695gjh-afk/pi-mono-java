/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.service.command;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.HelpCommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.ModelCommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.SessionCommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.SkillsCommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeSessionResponseAssembler;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeSessionView;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.AgentHelpResponseVO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.AvailableModelsResponseVO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.BoundSkillsResponseVO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.GetSessionResponseVO;

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
