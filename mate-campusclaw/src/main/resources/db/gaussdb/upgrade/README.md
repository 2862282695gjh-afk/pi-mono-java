# GaussDB Session schema upgrades

The database release platform is the only component allowed to execute files in this directory.
AgentService has DML-only credentials and must not run schema migrations at startup.

For every version transition, add the applicable files in this order:

1. `V<from>_to_V<to>__schema.sql` — transactional DDL where supported.
2. `V<from>_to_V<to>__data.sql` — bounded and restart-safe data conversion.
3. `V<from>_to_V<to>__verify.sql` — read-only checks that return no rows on success.

Each release change must document its compatible application window, rollback procedure, lock impact,
expected row counts, and batching strategy. Never edit an already released script; add a new transition.

The current full-install baseline is maintained in `../install/session_schema.sql`,
`../install/session_initial_data.sql`, and `../install/session_privileges.sql`. It may destructively
rebuild the Session schema and must never be used as an upgrade. There is no prior CampusClaw GaussDB
schema to upgrade, so this directory intentionally contains no migration SQL yet.
