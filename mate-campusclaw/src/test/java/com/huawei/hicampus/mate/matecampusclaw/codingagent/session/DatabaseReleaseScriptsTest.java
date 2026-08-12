/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Guards the externally released GaussDB Session schema against accidental drift.
 */
class DatabaseReleaseScriptsTest {

    private static final String INSTALL_ROOT = "db/gaussdb/install/";
    private static final Pattern CREATE_TABLE = Pattern.compile("(?im)^CREATE TABLE\\s+t_session");
    private static final Pattern COLUMN_COMMENT = Pattern.compile("(?im)^COMMENT ON COLUMN\\s+t_session");

    @Test
    void fullInstallContainsOnlyFourSessionTablesAndSeventeenColumnComments() throws IOException {
        String ddl = readResource(INSTALL_ROOT + "V1.0.0__session_schema.sql");

        assertEquals(4, CREATE_TABLE.matcher(ddl).results().count());
        assertEquals(17, COLUMN_COMMENT.matcher(ddl).results().count());
        assertTrue(ddl.contains("CREATE TABLE t_sessions"));
        assertTrue(ddl.contains("CREATE TABLE t_session_entries"));
        assertTrue(ddl.contains("CREATE TABLE t_session_sequences"));
        assertTrue(ddl.contains("CREATE TABLE t_session_materialized"));
        assertFalse(ddl.contains("t_branch_entries"));
        assertFalse(ddl.contains("t_branch_tips"));
        assertFalse(ddl.contains("t_entry_materialized"));
        assertFalse(ddl.toLowerCase(java.util.Locale.ROOT).contains("create table migrations"));
    }

    @Test
    void initialDataIsExplicitlyEmpty() throws IOException {
        String initialData = readResource(INSTALL_ROOT + "V1.0.0__session_initial_data.sql");

        assertTrue(initialData.contains("Intentionally empty"));
        assertFalse(Pattern.compile("(?im)^\\s*(INSERT|UPDATE|DELETE)\\s+")
                .matcher(initialData)
                .find());
    }

    @Test
    void runtimePrivilegesAreDmlOnly() throws IOException {
        String privileges = readResource(INSTALL_ROOT + "V1.0.0__session_privileges.sql");
        String normalized = privileges.toUpperCase(java.util.Locale.ROOT);

        assertTrue(normalized.contains("GRANT SELECT, INSERT, UPDATE, DELETE"));
        assertFalse(normalized.contains("GRANT CREATE"));
        assertFalse(normalized.contains("GRANT ALL"));
        assertFalse(normalized.contains("ALTER TABLE"));
        assertFalse(normalized.contains("DROP TABLE"));
    }

    private static String readResource(String path) throws IOException {
        try (InputStream input =
                DatabaseReleaseScriptsTest.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing test resource: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
