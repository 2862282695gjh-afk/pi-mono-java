/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.campusclaw.codingagent.runtimeapi.command.catalog.ResolvedCommandCatalog;
import com.campusclaw.codingagent.runtimeapi.command.type.CommandKind;
import com.campusclaw.codingagent.runtimeapi.dto.command.CommandSessionSnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.ResolvedCommandDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.SkillCommandSnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.vo.CommandListResponseVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommandDiscoveryResponseAssemblerTest {
    private final JsonMapper json = JsonMapper.builder().build();

    private final CommandDiscoveryResponseAssembler assembler = new CommandDiscoveryResponseAssembler();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void discovery_shouldProjectCurrentShape_inContractOrder(boolean running) throws Exception {
        var catalog = catalog(running, commands(running));
        var original = List.copyOf(catalog.list());
        var response = assembler.assemble(catalog);
        JsonNode body = json.valueToTree(response);
        var names = response.getCommands().stream()
                .map(CommandListResponseVO.DescriptorResponseVO::getName)
                .toList();
        assertThat(names)
                .containsExactlyElementsOf(
                        running
                                ? List.of("help", "status", "name", "model", "thinking", "skills")
                                : List.of(
                                        "help",
                                        "status",
                                        "name",
                                        "model",
                                        "thinking",
                                        "compact",
                                        "skills",
                                        "skill:alpha",
                                        "skill:zeta"));
        assertThat(body.properties()).extracting(Map.Entry::getKey).containsExactly("commands");
        for (JsonNode descriptor : body.get("commands")) {
            assertDescriptor(descriptor, running);
        }
        assertThat(catalog.list()).containsExactlyElementsOf(original);
        assertThatThrownBy(() -> response.getCommands().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void discovery_shouldReturnEmptyArray_forEmptyCatalog() throws Exception {
        assertThat(json.<JsonNode>valueToTree(assembler.assemble(catalog(false, List.of()))))
                .isEqualTo(json.readTree("{\"commands\":[]}"));
    }

    @Test
    void discovery_shouldRejectUnknownBuiltin_evenForSingleItem() {
        var unknown = command("future", false, true, true);
        var failure =
                assertThrows(IllegalStateException.class, () -> assembler.assemble(catalog(false, List.of(unknown))));
        assertThat(failure).hasMessageContaining("Unrecognized Builtin display order");
    }

    @Test
    void discovery_shouldOmitUnavailableItems_andUnavailableInput() throws Exception {
        var entries = List.of(command("name", false, false, true), command("model", false, true, false));
        assertThat(json.<JsonNode>valueToTree(assembler.assemble(catalog(false, entries))))
                .isEqualTo(
                        json.readTree(
                                """
                        {"commands":[{"name":"model","kind":"builtin","description":"description:model"}]}
                        """));
    }

    @Test
    void discovery_shouldNotExposeBuiltinFiles_orMissingInput() throws Exception {
        var source = command("name", false, true, true);
        var missing = new ResolvedCommandDTO("status", CommandKind.BUILTIN, "status", true, null, null, null);
        var body = json.valueToTree(assembler.assemble(catalog(false, List.of(source, missing))));
        assertThat(body.at("/commands/0/input").isMissingNode()).isTrue();
        assertThat(body.at("/commands/1/input")).isEqualTo(json.readTree("{\"hint\":\"[displayName]\"}"));
    }

    @Test
    void responseList_shouldDefensivelyCopyCallerCollection() {
        var values = new ArrayList<>(
                List.of(new CommandListResponseVO.DescriptorResponseVO("help", "builtin", "Help", null)));
        var response = new CommandListResponseVO(values);
        values.clear();
        assertThat(response.getCommands())
                .extracting(CommandListResponseVO.DescriptorResponseVO::getName)
                .containsExactly("help");
    }

    private void assertDescriptor(JsonNode descriptor, boolean running) throws Exception {
        String name = descriptor.get("name").asText();
        String hint = Map.of("name", "[displayName]", "model", "[modelId]", "thinking", "[on|off]")
                .get(name);
        boolean skill = name.startsWith("skill:");
        boolean withInput = skill || hint != null && (!running || name.equals("name"));
        assertThat(descriptor.get("kind").asText()).isEqualTo(skill ? "skill" : "builtin");
        assertThat(descriptor.get("description").asText()).isEqualTo("description:" + name);
        assertThat(descriptor.properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrderElementsOf(
                        withInput
                                ? List.of("name", "kind", "description", "input")
                                : List.of("name", "kind", "description"));
        if (withInput) {
            String input = skill ? "{\"hint\":\"[request]\",\"acceptsFiles\":true}" : "{\"hint\":\"" + hint + "\"}";
            assertThat(descriptor.get("input")).isEqualTo(json.readTree(input));
        }
    }

    private List<ResolvedCommandDTO> commands(boolean running) {
        var entries = new ArrayList<ResolvedCommandDTO>();
        for (String name : List.of(
                "help", "status", "name", "model", "thinking", "compact", "skills", "skill:alpha", "skill:zeta")) {
            boolean skill = name.startsWith("skill:");
            boolean available = !running || !skill && !name.equals("compact");
            boolean input = !running || !List.of("model", "thinking").contains(name);
            entries.add(command(name, skill, available, input));
        }
        Collections.reverse(entries);
        return entries;
    }

    private ResolvedCommandDTO command(String name, boolean skill, boolean available, boolean withInput) {
        var input = new ResolvedCommandDTO.InputDTO(
                "optional", withInput, "internal-code", true, "private-placeholder", List.of("private-suggestion"));
        var snapshot =
                new SkillCommandSnapshotDTO("agent-private", "v-private", "skill-private", "v-private", "private-body");
        return new ResolvedCommandDTO(
                name,
                skill ? CommandKind.SKILL : CommandKind.BUILTIN,
                "description:" + name,
                available,
                "private-reason",
                input,
                snapshot);
    }

    private ResolvedCommandCatalog catalog(boolean running, List<ResolvedCommandDTO> entries) {
        var session =
                new CommandSessionSnapshotDTO("session", "agent", running ? "running" : "idle", "model", false, 1L);
        return new ResolvedCommandCatalog(session, entries, Map.of());
    }
}
