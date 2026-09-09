/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.HelpCommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.ModelCommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.SessionCommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.SkillsCommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.SkillsCommandResultDTO.SkillDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeSessionResponseAssembler;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.SessionEtagFactory;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.AgentHelpResponseVO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.AvailableModelsResponseVO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.BoundSkillsResponseVO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.CompactionResponseVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CommandResponseAssemblerTest {
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    private final SessionEtagFactory etags = new SessionEtagFactory();

    private final RuntimeSessionResponseAssembler sessions = new RuntimeSessionResponseAssembler(etags);

    private final CommandResponseAssembler assembler = new CommandResponseAssembler(sessions);

    @ParameterizedTest
    @CsvSource({"idle,false", "idle,true", "running,false", "running,true"})
    void session_shouldReuseEntireResourceAndEtag_withoutInternalReceipt(String state, boolean changed) {
        var session = session(state);
        var result = new SessionCommandResultDTO(session, changed, changed ? 123L : null);
        var view = assembler.session(result);
        JsonNode body = json.valueToTree(view.resource());
        assertThat(body).isEqualTo(json.valueToTree(sessions.getView(session).resource()));
        assertThat(view.etag()).isEqualTo(etags.create("session-one", 8L));
        assertThat(body.properties())
                .extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "sessionId",
                        "agentId",
                        "displayName",
                        "modelId",
                        "state",
                        "thinking",
                        "lifetimeUsage",
                        "createdAt",
                        "updatedAt");
        assertThat(body.get("displayName").isNull()).isTrue();
        assertThat(body.get("state").asText()).isEqualTo(state);
        assertThat(body.at("/lifetimeUsage/input").asLong()).isEqualTo(11L);
        assertThat(body.at("/lifetimeUsage/totalTokens").asLong()).isEqualTo(41L);
        assertThat(body.at("/lifetimeUsage/cost/total").decimalValue()).isEqualByComparingTo("0.5");
        session.setDisplayName("later");
        session.getLifetimeUsage().setInput(99L);
        assertThat(view.resource().getDisplayName()).isNull();
        assertThat(view.resource().getLifetimeUsage().getInput()).isEqualTo(11L);
    }

    @Test
    void models_shouldReuseBusinessVo_andPreserveConfiguredOrder() throws Exception {
        var view = assembler.models(new ModelCommandResultDTO("unlisted", List.of("z/model", "a/model")));
        assertThat(view).isInstanceOf(AvailableModelsResponseVO.class);
        assertThat(json.<JsonNode>valueToTree(view))
                .isEqualTo(
                        json.readTree(
                                """
                {"currentModelId":"unlisted","models":["z/model","a/model"]}
                """));
        assertThatThrownBy(() -> view.getModels().add("other")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void help_shouldProjectOnlyGuideFields_andPreserveAuthorText() throws Exception {
        var result = new HelpCommandResultDTO(
                "Agent name", List.of("line one\nline two", "<script>text</script>"), List.of("case"));
        var view = assembler.help(result);
        assertThat(json.<JsonNode>valueToTree(view))
                .isEqualTo(
                        json.readTree(
                                """
                {"displayName":"Agent name","description":["line one\\nline two","<script>text</script>"],"userCases":["case"]}
                """));
        assertThatThrownBy(() -> view.getDescription().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.getUserCases().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void skills_shouldCopyBusinessItems_withoutDtoLeakage() throws Exception {
        var result = new SkillsCommandResultDTO(List.of(new SkillDTO("alpha", "first"), new SkillDTO("pdf", "PDF")));
        var view = assembler.skills(result);
        assertThat(json.<JsonNode>valueToTree(view))
                .isEqualTo(
                        json.readTree(
                                """
                {"skills":[{"name":"alpha","description":"first"},{"name":"pdf","description":"PDF"}]}
                """));
        assertThat(view.getSkills().getFirst()).isInstanceOf(BoundSkillsResponseVO.SkillResponseVO.class);
        assertThatThrownBy(() -> view.getSkills().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void compaction_shouldExposeOnlyBusinessChange(boolean compacted) throws Exception {
        assertThat(json.<JsonNode>valueToTree(new CompactionResponseVO(compacted)))
                .isEqualTo(json.readTree("{\"compacted\":" + compacted + "}"));
    }

    @Test
    void emptyResults_shouldRetainAllArrayFields_andNullableCurrentModel() throws Exception {
        assertThat(json.<JsonNode>valueToTree(assembler.help(new HelpCommandResultDTO("Agent", List.of(), List.of()))))
                .isEqualTo(json.readTree("{\"displayName\":\"Agent\",\"description\":[],\"userCases\":[]}"));
        assertThat(json.<JsonNode>valueToTree(assembler.skills(new SkillsCommandResultDTO(List.of()))))
                .isEqualTo(json.readTree("{\"skills\":[]}"));
        assertThat(json.<JsonNode>valueToTree(assembler.models(new ModelCommandResultDTO(null, List.of()))))
                .isEqualTo(json.readTree("{\"currentModelId\":null,\"models\":[]}"));
    }

    @Test
    void responseLists_shouldDefensivelyCopyCallerCollections() {
        var texts = new ArrayList<>(List.of("original"));
        var guide = new AgentHelpResponseVO("Agent", texts, texts);
        var skillItems = new ArrayList<>(List.of(new BoundSkillsResponseVO.SkillResponseVO("pdf", "PDF")));
        var skills = new BoundSkillsResponseVO(skillItems);
        texts.clear();
        skillItems.clear();
        assertThat(guide.getDescription()).containsExactly("original");
        assertThat(guide.getUserCases()).containsExactly("original");
        assertThat(skills.getSkills())
                .extracting(BoundSkillsResponseVO.SkillResponseVO::getName)
                .containsExactly("pdf");
    }

    private RuntimeSessionDTO session(String state) {
        var value = new RuntimeSessionDTO();
        value.setId("session-one");
        value.setAgentId("agent-one");
        value.setModelId("provider/model");
        value.setState(state);
        value.setThinking(true);
        value.setResourceVersion(8L);
        value.setCreatedAt(OffsetDateTime.parse("2026-09-01T00:00:00Z"));
        value.setUpdatedAt(OffsetDateTime.parse("2026-09-07T00:00:00Z"));
        value.setCwd("/private/path");
        value.setMetadata("private metadata");
        value.setActiveLeafId("private-leaf");
        value.getLifetimeUsage().setInput(11L);
        value.getLifetimeUsage().setTotalTokens(41L);
        value.getLifetimeUsage().setCostTotal(new BigDecimal("0.5"));
        return value;
    }
}
