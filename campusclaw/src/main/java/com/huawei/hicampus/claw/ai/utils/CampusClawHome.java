/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.ai.utils;

import java.nio.file.Path;

import com.huawei.hicampus.claw.common.constant.ClawConstants;

/**
 * 解析各模块共享的 CampusClaw 用户级配置根目录。
 *
 * <p>依次优先使用系统属性 {@code campusclaw.home}、环境变量 {@code CAMPUSCLAW_HOME}，
 * 最后回退至 {@code ~/.campusclaw}。固定名称由底层 common 模块的 ClawConstants 定义，
 * 本类仅负责路径解析，供 Agent 与服务模块复用。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public final class CampusClawHome {

    private CampusClawHome() {}

    /**
     * 返回用户级配置根目录，默认值为 {@code ~/.campusclaw}。
     *
     * @return 解析后的配置根目录
     */
    public static Path baseDir() {
        return resolveBaseDir(
                System.getProperty(ClawConstants.Home.PROPERTY),
                System.getenv(ClawConstants.Home.ENVIRONMENT_VARIABLE),
                System.getProperty("user.home"));
    }

    /**
     * 按三个输入值的优先级解析配置根目录，不读取或修改进程状态。
     *
     * @param homeProperty 系统属性 {@code campusclaw.home} 的值，可为空
     * @param homeEnv 环境变量 {@code CAMPUSCLAW_HOME} 的值，可为空
     * @param userHome 系统属性 {@code user.home} 的值，用于构造默认路径
     * @return 解析后的配置根目录
     */
    static Path resolveBaseDir(String homeProperty, String homeEnv, String userHome) {
        if (homeProperty != null && !homeProperty.isBlank()) {
            return Path.of(homeProperty);
        }
        if (homeEnv != null && !homeEnv.isBlank()) {
            return Path.of(homeEnv);
        }
        return Path.of(userHome, ClawConstants.Home.CONFIG_DIRECTORY_NAME);
    }

    /**
     * 返回用户级 Agent 目录，即 {@code <baseDir>/agent}。
     *
     * @return 解析后的 Agent 配置目录
     */
    public static Path agentDir() {
        return baseDir().resolve(ClawConstants.Home.AGENT_DIRECTORY_NAME);
    }
}
