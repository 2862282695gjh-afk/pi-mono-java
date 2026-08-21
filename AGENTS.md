# Repository Agent Instructions

## Scope and engineering source of truth

- These instructions apply to the entire repository.
- Before changing code, tests, build files, or design documents, read
  `CLAUDE.md` and the relevant source and design files. `CLAUDE.md` is the
  detailed repository handbook for builds, Java conventions, tests, design
  records, and the `mate-campusclaw` mirror.
- Follow more specific `AGENTS.md` or `AGENTS.override.md` files if they are
  added under a subdirectory.
- Treat macOS and Linux as the only supported local launch and installation
  platforms. Do not add or maintain Windows-specific batch, PowerShell,
  `mvnw.cmd`, or Task Scheduler launch and installation entry points unless a
  later user instruction changes the platform policy. The POSIX `mvnw` may
  retain upstream Cygwin/MinGW compatibility for best-effort Windows builds;
  that compatibility carries no Windows support or validation commitment.
- Preserve unrelated user changes. Never stage, rewrite, stash, or discard
  them unless the user explicitly asks. Use a separate Git worktree when the
  current worktree is dirty or is needed for another task.

## Java comment and constant organization

- Use `// ...` for brief comments attached to private fields, private
  constants, and similar implementation details, including regular-expression
  constants.
- Do not use one-line Javadoc comments such as `/** ... */` for those brief
  implementation comments.
- Continue to use Javadoc where required for top-level public types and public
  API contracts by `CLAUDE.md` or higher-level instructions.
- Define reusable constants in the domain-specific `*Constants` or `*Patterns`
  file that owns the concept. Keep regular-expression strings and their
  compiled `Pattern` objects together in that source of truth, and make all
  consumers reference it instead of duplicating literals or calls to
  `Pattern.compile`.
- Do not create an unscoped global `Constants` container or place a domain
  constraint in a protocol-specific constants class unless that protocol owns
  the constraint. Constants used only by one class as implementation details
  may remain private in that class.

## Mandatory Git publishing workflow

- Treat repository administrator access as a capability, not as the normal
  publishing path.
- Never commit or push directly to `main` or `master`.
- Start every change from the latest `origin/main` on a dedicated
  `codex/<topic>` branch. Keep one topic per branch and pull request.
- Stage only explicit task files. Do not use `git add -A` in a mixed worktree.
- Follow the Conventional Commit rules in `CLAUDE.md`.
- Push only the topic branch, then create a Draft pull request targeting
  `main`. A pushed branch without a pull request is not a completed delivery.
- The pull request body must explain what changed, why it changed, user or
  developer impact, and the validation performed.
- Before reporting completion, verify that the pull request exists and provide
  its URL. Keep the pull request as Draft unless the user explicitly asks to
  mark it ready or merge it.
- Do not use administrator bypass, `git push --no-verify`, force-push, or a
  direct update of the default branch to avoid this workflow. An exception
  requires an explicit instruction from the user in the current task; a
  generic request to "commit", "push", "publish", or "finish" is not an
  exception.
- If `main` advances, merge the latest `origin/main` into the topic branch and
  rerun relevant validation. Do not rewrite shared branch history.

## Validation and handoff

- Inspect `git status -sb`, the task-scoped diff, and the staged diff before
  committing.
- Run `git diff --check` and the smallest relevant build, formatting, and test
  commands described in `CLAUDE.md`. Report any validation that could not run.
- When `modules/*` changes, update and verify the `mate-campusclaw` mirror as
  required by `CLAUDE.md` before pushing.
- Do not claim a task is complete when required tests, the branch push, or the
  Draft pull request is still missing. State the exact remaining blocker.
