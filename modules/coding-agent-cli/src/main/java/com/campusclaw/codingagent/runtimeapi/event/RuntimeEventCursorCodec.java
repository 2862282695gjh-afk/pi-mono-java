/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 使用 AES-GCM 签发不可解释、绑定 Session 且有有效期的事件分页游标。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class RuntimeEventCursorCodec {
    private static final Logger log = LoggerFactory.getLogger(RuntimeEventCursorCodec.class);

    private static final String PREFIX = "page_";

    private static final int IV_LENGTH = 12;

    private static final int TAG_BITS = 128;

    private final RuntimeEventProperties properties;

    private final Clock clock;

    private final SecureRandom random = new SecureRandom();

    private final byte[] key;

    public RuntimeEventCursorCodec(RuntimeEventProperties properties, Clock clock) {
        if (properties.getCursorTtl() == null
                || properties.getCursorTtl().isZero()
                || properties.getCursorTtl().isNegative()) {
            throw new IllegalArgumentException("event cursor TTL must be positive");
        }
        this.properties = properties;
        this.clock = clock;
        this.key = createKey(properties.getCursorSecret());
    }

    public String encode(String sessionId, long afterSeq) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, iv);
            byte[] encrypted = cipher.doFinal(serialize(sessionId, afterSeq));
            byte[] token = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, token, iv.length, encrypted.length);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        } catch (Exception error) {
            throw new IllegalStateException("failed to encode event cursor", error);
        }
    }

    public long decode(String token, String expectedSessionId) {
        try {
            if (token == null || !token.startsWith(PREFIX)) {
                throw invalidCursor(null);
            }
            byte[] bytes = Base64.getUrlDecoder().decode(token.substring(PREFIX.length()));
            if (bytes.length <= IV_LENGTH) {
                throw invalidCursor(null);
            }
            byte[] iv = Arrays.copyOf(bytes, IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(bytes, IV_LENGTH, bytes.length);
            byte[] clear = cipher(Cipher.DECRYPT_MODE, iv).doFinal(encrypted);
            return deserialize(clear, expectedSessionId);
        } catch (RuntimeApiException error) {
            throw error;
        } catch (Exception error) {
            throw invalidCursor(error);
        }
    }

    private byte[] serialize(String sessionId, long afterSeq) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeByte(1);
            output.writeUTF(sessionId);
            output.writeLong(afterSeq);
            output.writeLong(clock.instant().plus(properties.getCursorTtl()).getEpochSecond());
        }
        return bytes.toByteArray();
    }

    private long deserialize(byte[] bytes, String expectedSessionId) throws Exception {
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = input.readUnsignedByte();
            String sessionId = input.readUTF();
            long afterSeq = input.readLong();
            long expiresAt = input.readLong();
            if (version != 1
                    || !sessionId.equals(expectedSessionId)
                    || afterSeq < 0
                    || expiresAt <= clock.instant().getEpochSecond()
                    || input.available() != 0) {
                throw invalidCursor(null);
            }
            return afterSeq;
        }
    }

    private Cipher cipher(int mode, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
        return cipher;
    }

    private byte[] createKey(String secret) {
        try {
            if (secret != null && !secret.isBlank()) {
                return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            }
            byte[] generated = new byte[32];
            random.nextBytes(generated);
            log.warn("Event cursor secret is not configured; cursors will expire after process restart");
            return generated;
        } catch (Exception error) {
            throw new IllegalStateException("failed to initialize event cursor key", error);
        }
    }

    private static RuntimeApiException invalidCursor(Throwable cause) {
        return new RuntimeApiException(RuntimeErrorCode.INVALID_EVENT_LIST_QUERY, cause);
    }
}
