/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.util.List;

import com.campusclaw.codingagent.runtimeapi.dto.CommittedEventDTO;

/**
 * 将一次数据库补读结果排入一个独立响应。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@FunctionalInterface
public interface RuntimeCommittedResultDelivery {
    /**
     * 排入尚未投递的完整事件，并在终态到达时关闭响应。
     *
     * @param events 严格按内部序号排列的完整事件
     * @param terminal 当前响应绑定范围是否已经结束
     * @return 响应是否接收了本次结果
     */
    boolean deliver(List<CommittedEventDTO> events, boolean terminal);
}
