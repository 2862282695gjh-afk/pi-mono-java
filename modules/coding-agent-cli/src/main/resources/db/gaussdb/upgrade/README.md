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
rebuild the Session schema and must never be used as an upgrade.

## V1 to V2 Events migration

`V1_to_V2__schema.sql`, `V1_to_V2__data.sql`, and `V1_to_V2__verify.sql` are the explicit exception for
upgrading persisted Session events to the Events v2 public contract. They do not introduce a general
application-side migration runner.

The schema step is compatible with the V1 application because it only adds tables, indexes, and
migration-only validation functions that the V1 application never calls. Do not deploy the V2 reader
until verification returns no rows. Once the V2 application writes public events,
rolling the application back to V1 leaves the new tables intact; do not drop or reverse-convert them.
Before any V2 write, a DDL rollback may drop the four new `t_session_event*` tables after their reviewed
input has been archived according to the release procedure. It may also drop
`f_session_event_migration_gaps`, `f_validate_session_event_v2`, and the `f_session_event_*` helper
functions after the migration inputs no longer need validation. Drop the gap function first, then the
public validator, then its helpers, and do not use `CASCADE`. Never use the destructive install script as
a rollback.

Run the transition as follows:

1. Stop every CampusClaw instance that can write Sessions. Wait for accepted executions to reach a
   durable terminal state. The data step also locks the Session, sequence, Entry, public event, projection,
   and review tables, but the maintenance window prevents deadlocks and long request stalls.
2. Run the schema script, then reapply `../install/session_privileges.sql` through the release platform
   so the runtime role can access the public event and projection tables. Keep both migration input tables
   release-owner-only. Run the verify script to obtain migration gaps. Its output contains only `session_id`,
   `anchor_entry_id`, the internal type, and a fixed reason; it never returns payload, review text, Skill
   content, or credentials.
3. Populate `t_session_event_migration_review` for every record that cannot be mapped automatically.
   Set the exact public event count and a non-sensitive reason. For every positive count, populate
   `t_session_event_migration_events` with contiguous `event_order` values starting at one, stable
   `event_id` values, saved millisecond timestamps, a V2 public type, and a payload already reviewed
   against the public event contract.
4. Run the data script repeatedly, then run the verify script. Each pass commits at most 500 complete
   Sessions in Session ID order. The largest Session remains one atomic unit. Continue until verification
   returns no rows, then deploy V2 and restore traffic.

The data script automatically maps valid model and thinking configuration changes and valid manual
compactions. It marks known connection, delta, tree-control, and internal lifecycle records as private.
It never guesses a user message, tool call, tool result, public thinking summary, automatic compaction
source, or idle reason. In particular, a legacy Skill `user.message` contains the expanded private body,
not the pre-expansion receipt. Supply a reviewed receipt from a trusted source or leave the Session
unmigrated; never copy the expanded message into the public staging table. A reviewed private thinking
record uses `event_count=0` and a reason that records why the provider content is not a public summary.

For each migrated old Entry, expect exactly one row in `t_session_event_projection`. Its `event_count`
must equal the number of rows anchored to that Entry in `t_session_events`. Automatically private records
have zero events; valid configuration changes and manual compactions have one. Reviewed records use the
declared exact count. `t_session_sequences.next_seq` increases by the number of inserted public events.
Successful review input is deleted in the same transaction, so no duplicate Skill receipt or public
payload remains. Re-running a completed pass does not allocate new IDs, duplicate events, or advance the
sequence again.

The schema script is restart-safe during the same stopped-write maintenance window. Tables and indexes
use `IF NOT EXISTS`; payload validation and gap functions use `CREATE OR REPLACE` without changing their
signatures. `f_validate_session_event_v2(type, payload)` is the single payload rule used by migration and
verification. It returns false without logging or returning payload content when a public type, exact
field set, nested content, enum, Java `long`, Java UTF-16 string limit, or error-field condition is invalid.
The data step refuses the complete Session if a staged or existing event fails this rule, if an existing
projection count differs from its anchored events, or if an existing public type is incompatible with the
old Entry type. It also refuses `event_count=0` for an old type that requires a public event; only explicitly
reviewed optional public thinking may use zero. `f_session_event_migration_gaps()` exposes the same failures
through fixed reasons, and the verification script only orders and returns those safe columns.

Run the payload validator regression against a disposable database before approving the schema step:

```shell
psql -X -v ON_ERROR_STOP=1 -d <disposable-database> \
  -f test/V1_to_V2__payload_validation_regression.sql
```

The regression recreates and drops only `campusclaw_events_v2_payload_test` in that database. It checks
all eleven public event types and rejects missing, null, extra, malformed, private, overflow, and UTF-16
over-limit fields. Any mismatch raises an exception and leaves the isolated schema available for diagnosis.

Run the end-to-end migration regression in the same disposable database:

```shell
psql -X -v ON_ERROR_STOP=1 -d <disposable-database> \
  -f test/V1_to_V2__migration_regression.sql
```

This fixture proves exact one-to-many mapping, an explicit private thinking record with zero public events,
a reviewed public thinking summary, stable IDs and sequences across reruns, and fail-closed handling for an
unknown type, a legacy Skill message without its original receipt, malformed public payload, a forbidden
zero count, incompatible staged and existing old-to-public event types, an existing projection with a
wrong count, and a `sourceEventId` that exists only in another Session. It also proves that later automatic
records in either invalid Session are not partially migrated. It prints only synthetic IDs, types, and fixed
gap reasons. A successful run drops
`campusclaw_events_v2_migration_test`; a failed assertion keeps it for diagnosis.
