/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.session;

import java.security.SecureRandom;

/**
 * 使用 Crockford Base32 随机值生成 26 字符 Session 标识。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public class RandomSessionIdGenerator implements SessionIdGenerator {
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private final SecureRandom random = new SecureRandom();

    @Override
    public String nextId() {
        char[] id = new char[26];
        for (int index = 0; index < id.length; index++) {
            id[index] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(id);
    }
}
