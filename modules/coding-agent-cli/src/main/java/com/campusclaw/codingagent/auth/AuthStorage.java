/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service that persists per-provider {@link Credential} entries in a JSON file under the
 * user's auth directory with POSIX 600 permissions where supported. Exposes get/set/remove/list
 * lookup plus a convenience accessor that flattens {@link Credential.ApiKey} and
 * {@link Credential.OAuth} to a single token string.
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/13]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class AuthStorage {
    private static final Logger log = LoggerFactory.getLogger(AuthStorage.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    /**
     * Reified map type so Jackson preserves the {@link Credential} polymorphic discriminator
     * on BOTH read and write. Writing the raw map without this reference loses the {@code type}
     * field (Jackson cannot statically see the element is polymorphic), which makes round-trips
     * fail to deserialise — reading also uses it for symmetry.
     */
    private static final TypeReference<Map<String, Credential>> MAP_TYPE = new TypeReference<>() {};

    private final Path authFile;

    /**
     * Production constructor — writes under {@code ~/.campusclaw/agent/auth.json}.
     */
    public AuthStorage() {
        this(com.campusclaw.codingagent.config.AppPaths.AUTH_FILE);
    }

    /**
     * Test seam — allows directing persistence at an arbitrary path so tests can use a
     * {@code @TempDir} location instead of the real user home.
     *
     * @param authFile path to the JSON file backing the store
     */
    AuthStorage(Path authFile) {
        this.authFile = authFile;
    }

    /**
     * Get credential for a provider.
     *
     * @param provider the provider
     * @return the result
     */
    public Optional<Credential> get(String provider) {
        return load().map(m -> m.get(provider));
    }

    /**
     * Get API key string for a provider (from ApiKey credential or OAuth accessToken).
     *
     * @param provider the provider
     * @return the result
     */
    public Optional<String> getApiKey(String provider) {
        return get(provider).map(c -> switch (c) {
            case Credential.ApiKey ak -> ak.key();
            case Credential.OAuth oa -> oa.accessToken();
        });
    }

    /**
     * Store a credential.
     *
     * @param provider the provider
     * @param credential the credential
     */
    public void set(String provider, Credential credential) {
        var map = load().orElse(new LinkedHashMap<>());
        map.put(provider, credential);
        save(map);
    }

    /**
     * Remove a credential.
     *
     * @param provider the provider
     */
    public void remove(String provider) {
        var map = load().orElse(new LinkedHashMap<>());
        map.remove(provider);
        save(map);
    }

    /**
     * Check if a provider has credentials stored.
     *
     * @param provider the provider
     * @return the result
     */
    public boolean has(String provider) {
        return load().map(m -> m.containsKey(provider)).orElse(false);
    }

    /**
     * List all stored provider names.
     *
     * @return the result
     */
    public Set<String> list() {
        return load().map(Map::keySet).orElse(Set.of());
    }

    private Optional<Map<String, Credential>> load() {
        if (!Files.exists(authFile)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(authFile, StandardCharsets.UTF_8);
            Map<String, Credential> map = MAPPER.readValue(json, MAP_TYPE);
            return Optional.of(new LinkedHashMap<>(map));
        } catch (Exception e) {
            log.warn("Failed to read auth file: {}", authFile, e);
            return Optional.empty();
        }
    }

    private void save(Map<String, Credential> map) {
        try {
            Files.createDirectories(authFile.getParent());
            String json = MAPPER.writerFor(MAP_TYPE).withDefaultPrettyPrinter().writeValueAsString(map);
            Files.writeString(authFile, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // Set owner-only permissions (600)
            try {
                Files.setPosixFilePermissions(authFile, OWNER_ONLY);
            } catch (UnsupportedOperationException e) {
                // Windows doesn't support POSIX permissions
                log.debug("skipped owner-only chmod on {} (non-POSIX FS)", authFile, e);
            }
        } catch (IOException e) {
            log.error("Failed to save auth file: {}", authFile, e);
        }
    }
}
