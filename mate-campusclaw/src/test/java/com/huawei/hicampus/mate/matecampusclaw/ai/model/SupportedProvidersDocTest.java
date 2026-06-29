/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.ai.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Pins the documented supported-provider list to the code-derived source of truth so the
 * two cannot silently drift.
 *
 * <p>The repo-root {@code README.md} carries a hidden, machine-readable canonical id block
 * delimited by {@code BEGIN supported-providers} / {@code END supported-providers}. This test
 * parses that block and asserts it matches exactly the set of
 * {@link com.huawei.hicampus.mate.matecampusclaw.ai.types.Provider} ids that have at least one built-in model in
 * {@link ModelRegistry#builtInModels()}. Adding or removing a built-in provider without
 * updating the README block turns this test red.
 *
 * <p>When the repo-root {@code README.md} cannot be located (e.g. a detached single-module
 * build that does not ship the docs), the test skips rather than failing.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/29]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class SupportedProvidersDocTest {

    private static final String BEGIN_MARKER = "BEGIN supported-providers";

    private static final String END_MARKER = "END supported-providers";

    @Test
    void readmeBlockMatchesBuiltInProviders() throws IOException {
        Optional<Path> readme = locateRepoFile("README.md");
        assumeTrue(readme.isPresent(), "repo-root README.md not found; skipping doc-consistency check");

        List<String> codeProviders = ModelRegistry.builtInModels().stream()
                .map(model -> model.provider().value())
                .distinct()
                .sorted()
                .toList();

        List<String> docProviders = parseCanonicalBlock(Files.readString(readme.get(), StandardCharsets.UTF_8));

        assertEquals(
                codeProviders,
                docProviders,
                "README 'supported-providers' block is out of sync with ModelRegistry built-ins. "
                        + "Update the BEGIN/END supported-providers id list (and the table beneath it) in README.md.");
    }

    /**
     * Extracts the comma/whitespace-separated provider ids between the BEGIN/END markers.
     *
     * @param readmeContent the full README.md text
     * @return the sorted, de-duplicated list of provider ids declared in the canonical block
     */
    private static List<String> parseCanonicalBlock(String readmeContent) {
        int begin = readmeContent.indexOf(BEGIN_MARKER);
        int end = readmeContent.indexOf(END_MARKER);
        assertTrue(
                begin >= 0 && end > begin, "README.md is missing the 'BEGIN/END supported-providers' canonical block");
        String body = readmeContent.substring(begin + BEGIN_MARKER.length(), end);
        return Arrays.stream(body.split("[,\\s]+"))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Walks up from the working directory until a directory containing {@code fileName} is found.
     *
     * @param fileName the repo-root file to locate (e.g. {@code README.md})
     * @return the located file, or empty if no ancestor directory contains it
     */
    private static Optional<Path> locateRepoFile(String fileName) {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
            dir = dir.getParent();
        }
        return Optional.empty();
    }
}
