/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.edit;

/**
 * Structured details returned alongside the Edit tool result.
 *
 * @param diff             unified diff of the changes made
 * @param firstChangedLine 1-indexed line number of the first change, or null
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public record EditToolDetails(String diff, Integer firstChangedLine) {}
