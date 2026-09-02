/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import java.security.SecureRandom;

/**
 * 生成带 entry_ 前缀的随机 Entry 标识。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public class RandomRuntimeEntryIdGenerator implements RuntimeEntryIdGenerator {
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private final SecureRandom random = new SecureRandom();

    @Override
    public String nextId() {
        char[] suffix = new char[26];
        for (int index = 0; index < suffix.length; index++) {
            suffix[index] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return "entry_" + new String(suffix);
    }
}
