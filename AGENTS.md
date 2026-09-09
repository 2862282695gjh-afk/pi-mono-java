# Repository Agent Instructions

## Scope and engineering source of truth

- These instructions govern this CampusClaw repository and its tracked files.
  If this checkout is in a home directory, personal agent configuration,
  downloaded material and independent nested repositories are outside this
  project's Java build, mirror and publishing workflow.
- Use `CLAUDE.md` as a task-specific reference: Build & Run for build changes,
  Architecture for service boundaries, Java conventions for Java edits, and
  the mirror section when changing `modules/*` or generated mirror content.
  Read the source and design records needed for the affected behavior; a
  typo or documentation edit does not require a whole-repository survey.
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

## Execution and completion

Complete the requested change, affected checks and required delivery within
its authorized scope. Make routine reversible implementation decisions and
continue; ask only when missing information materially changes the outcome or
an action exceeds existing authorization. Continue independent work while
waiting. An implementation is not finished while relevant validation remains.

User instructions take precedence over skill workflow and style preferences,
subject to platform permissions. Select skills for their actual contribution
and read only the references needed for the task. If a skill blocks progress,
identify the file and exact instruction instead of inventing an approval gate.

## Java comment, constant, and validation organization

- Use `// ...` for brief comments attached to private fields, private
  constants, and similar implementation details, including regular-expression
  constants.
- Do not use one-line Javadoc comments such as `/** ... */` for those brief
  implementation comments.
- Continue to use Javadoc where required for top-level public types and public
  API contracts by `CLAUDE.md` or higher-level instructions.
- Define shared business constants in
  `modules/common/src/main/java/com/campusclaw/common/constant/ClawConstants.java`.
  Use nested domain groups such as `ClawConstants.Skill` and
  `ClawConstants.RuntimeApi`; keep regex strings and compiled `Pattern` objects
  together and make all consumers reuse that source. Do not keep forwarding
  aliases or separate domain constants/patterns files.
- Keep `common` at the same package level as `ai`, `agent`, `cron`, and
  `codingagent`. Its Maven module must not depend on business modules; modules
  that directly consume its constants must declare the common dependency.
- Class-private parsing and algorithm details may remain in their owning
  classes. Preserve typed enums and singleton instances in their owning types,
  and keep deployment-varying values in injected configuration rather than
  moving them into `ClawConstants`.
- Use standard Jakarta Bean Validation annotations for constraints on request
  VOs and scalar Controller parameters, including path variables, when the
  standard annotations can express the rule. Ensure method validation is
  active and map validation exceptions to stable API errors in the centralized
  exception handler.
- Do not implement those standard parameter checks with an imperative
  Validator utility that every Controller must call. Do not introduce a VO
  solely to wrap one scalar path or query parameter for validation.

## Spring dependency injection

- Use constructor injection for required Spring-managed dependencies and keep
  the corresponding fields `private final`.
- When a Spring bean declares exactly one constructor, omit `@Autowired` because
  Spring selects that constructor automatically.
- When a Spring bean declares multiple constructors, exactly one constructor
  must be the Spring injection entry point and that constructor must be
  annotated with `@Autowired`. Keep test-only or convenience constructors
  unannotated and no more visible than their consumers require.
- Do not replace constructor injection with `@Resource` field or setter
  injection merely to avoid constructor selection. Use `@Resource(name =
  "...")` only when an integration contract genuinely requires name-based
  resource lookup, and document that exception locally.

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
- Run `git diff --check` and the checks relevant to the changed behavior.
  For documentation or prompt-only edits, inspect formatting, links, scope
  and instruction consistency; Java builds and mirror compilation are not
  required. For Java changes, use the relevant `CLAUDE.md` commands and
  required project checks. Report anything that could not run.
- Once the affected checks pass, publish the result. Broaden or repeat tests
  only for new changes, failures or a concrete unresolved concern; do not add
  tests that merely restate low-impact edits.
- When `modules/*` changes, update and verify the `campusclaw` mirror as
  required by `CLAUDE.md` before pushing.
- Do not claim a task is complete when required tests, the branch push, or the
  Draft pull request is still missing. State the exact remaining blocker.
