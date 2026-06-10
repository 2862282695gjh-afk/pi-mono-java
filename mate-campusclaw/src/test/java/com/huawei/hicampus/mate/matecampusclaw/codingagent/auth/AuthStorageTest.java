/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link AuthStorage}. Production resolves the auth file via the static
 * {@code AppPaths.AUTH_FILE}; the package-private constructor accepts an explicit path so
 * tests can redirect persistence to a {@link TempDir} without touching the real user home.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/09]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class AuthStorageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    private Path authFile;
    private AuthStorage storage;

    @BeforeEach
    void freshStore() {
        authFile = tmp.resolve("auth.json");
        storage = new AuthStorage(authFile);
    }

    @Nested
    class Get {

        @Test
        void missingFileYieldsEmpty() {
            assertThat(storage.get("anthropic")).isEmpty();
            assertThat(storage.getApiKey("anthropic")).isEmpty();
        }

        @Test
        void hasReportsFalseWhenAbsent() {
            assertThat(storage.has("anthropic")).isFalse();
        }

        @Test
        void listEmptyWhenAbsent() {
            assertThat(storage.list()).isEmpty();
        }

        @Test
        void malformedFileYieldsEmpty() throws Exception {
            Files.writeString(authFile, "{not valid json");
            assertThat(storage.get("anthropic")).isEmpty();
            assertThat(storage.list()).isEmpty();
            assertThat(storage.has("anthropic")).isFalse();
        }
    }

    @Nested
    class SetAndRoundTrip {

        @Test
        void setApiKeyPersistsAndReturns() {
            storage.set("anthropic", new Credential.ApiKey("sk-test"));

            assertThat(storage.has("anthropic")).isTrue();
            assertThat(storage.list()).containsExactly("anthropic");
            assertThat(storage.getApiKey("anthropic")).contains("sk-test");
            assertThat(storage.get("anthropic")).hasValueSatisfying(c -> {
                assertThat(c).isInstanceOf(Credential.ApiKey.class);
                assertThat(((Credential.ApiKey) c).key()).isEqualTo("sk-test");
            });
        }

        @Test
        void setOAuthPersistsAccessTokenAsApiKey() {
            storage.set("openai", new Credential.OAuth("tok-1", "refresh-1", 1_700_000_000L));

            assertThat(storage.getApiKey("openai")).contains("tok-1");
            assertThat(storage.get("openai")).hasValueSatisfying(c -> {
                assertThat(c).isInstanceOf(Credential.OAuth.class);
                Credential.OAuth oa = (Credential.OAuth) c;
                assertThat(oa.accessToken()).isEqualTo("tok-1");
                assertThat(oa.refreshToken()).isEqualTo("refresh-1");
                assertThat(oa.expiresAt()).isEqualTo(1_700_000_000L);
            });
        }

        @Test
        void multipleProvidersListedInInsertionOrder() {
            storage.set("anthropic", new Credential.ApiKey("a"));
            storage.set("openai", new Credential.ApiKey("b"));
            storage.set("google", new Credential.ApiKey("c"));

            assertThat(storage.list()).containsExactly("anthropic", "openai", "google");
        }

        @Test
        void setOverwritesExistingProvider() {
            storage.set("anthropic", new Credential.ApiKey("old"));
            storage.set("anthropic", new Credential.ApiKey("new"));

            assertThat(storage.getApiKey("anthropic")).contains("new");
            assertThat(storage.list()).containsExactly("anthropic");
        }

        @Test
        void persistedFileIsValidJsonMap() throws Exception {
            storage.set("anthropic", new Credential.ApiKey("sk-1"));

            assertThat(Files.exists(authFile)).isTrue();
            String json = Files.readString(authFile);
            Map<String, Object> parsed = MAPPER.readValue(json, new TypeReference<>() {});
            assertThat(parsed).containsKey("anthropic");
        }

        @Test
        void persistsAcrossInstances() {
            storage.set("anthropic", new Credential.ApiKey("sk-1"));

            AuthStorage other = new AuthStorage(authFile);
            assertThat(other.has("anthropic")).isTrue();
            assertThat(other.getApiKey("anthropic")).contains("sk-1");
        }
    }

    @Nested
    class Remove {

        @Test
        void removeExistingProvider() {
            storage.set("anthropic", new Credential.ApiKey("sk-1"));
            storage.remove("anthropic");

            assertThat(storage.has("anthropic")).isFalse();
            assertThat(storage.list()).isEmpty();
        }

        @Test
        void removeMissingProviderLeavesOthers() {
            storage.set("openai", new Credential.ApiKey("b"));
            storage.remove("does-not-exist");

            assertThat(storage.has("openai")).isTrue();
            assertThat(storage.list()).containsExactly("openai");
        }

        @Test
        void removeOnEmptyStoreCreatesEmptyFile() {
            storage.remove("missing");

            // remove() always writes back (per source), even on an empty map.
            assertThat(Files.exists(authFile)).isTrue();
            assertThat(storage.list()).isEmpty();
        }
    }

    @Nested
    class DefaultConstructor {

        @Test
        void defaultConstructorBindsToAppPathsLocation() {
            // Smoke test — just verifies the no-arg path resolves without throwing.
            // We don't read or write anything: the production file may or may not exist.
            assertThatNoException().isThrownBy(AuthStorage::new);
        }
    }
}
