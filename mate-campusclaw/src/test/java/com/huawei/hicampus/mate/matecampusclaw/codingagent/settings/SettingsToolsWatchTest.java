/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class SettingsToolsWatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesToolsWatchEnabled() throws Exception {
        Settings settings = MAPPER.readValue("{\"tools\":{\"watch\":{\"enabled\":true}}}", Settings.class);

        assertThat(settings.tools().watch().enabled()).isTrue();
    }
}
