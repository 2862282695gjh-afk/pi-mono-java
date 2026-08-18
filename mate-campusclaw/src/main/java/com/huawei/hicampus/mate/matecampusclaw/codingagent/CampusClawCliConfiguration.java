/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.config.ToolExecutionProperties;

import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Profile;

/**
 * CampusClaw CLI 专用 Spring 配置，不加载 HTTP Runtime 和数据库组件。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@SpringBootConfiguration
@Profile("campusclaw-cli")
@EnableConfigurationProperties(ToolExecutionProperties.class)
@EnableAutoConfiguration(
        exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            MybatisAutoConfiguration.class
        })
@ComponentScan(
        basePackages = "com.huawei.hicampus.mate.matecampusclaw",
        excludeFilters = {
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = CampusClawApplication.class),
            @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.campusclaw\\.codingagent\\.runtimeapi\\..*"),
            @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.campusclaw\\.codingagent\\.controlplane\\..*"),
            @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.campusclaw\\.agent\\.controlplane\\..*")
        })
public class CampusClawCliConfiguration {}
