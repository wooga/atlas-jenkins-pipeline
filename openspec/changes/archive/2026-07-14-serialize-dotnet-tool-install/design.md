## Context

`withDotnetTool`/`runDotnetTool` redirect `NUGET_PACKAGES`/`DOTNET_CLI_HOME` to a single
per-agent shared cache (`Dotnet.groovy:nugetHomeEnv()`), so that repeated tool installs across
jobs reuse a warm cache instead of each pipeline restoring into its own throwaway location.
That sharing is exactly what caused the observed race: two concurrent `dotnet tool install`
invocations on the same agent can both try to write into the same package folder under
`NUGET_PACKAGES`. `dotnet-sdk-provisioning` already solved the equivalent problem for SDK
installs with a self-healing `mkdir` lock (`resources/dotnet/dotnet-install.sh:acquire_lock`);
this change ports the same pattern to tool installs.

A second, structurally identical race surfaced during real-Jenkins verification of that fix:
`ensureNuGetSource()` registers the org's NuGet feed into a workspace-relative `./nuget.config`,
creating it via `dotnet new nugetconfig` if missing. Two runs sharing a workspace (confirmed by
the requester: parallel stages of the same job, same workspace) can both see the file missing
and both attempt `dotnet new nugetconfig`, which refuses to overwrite an existing file — the
loser fails with exit code 73. Same check-then-act shape as the tool-install race, different
contended resource (a workspace-relative file rather than the per-agent shared cache), so it
gets its own lock rather than being folded into the tool-install one.

## Goals / Non-Goals

**Goals:**
- Serialize concurrent `dotnet tool install` invocations on the same unix/macOS agent so they
  cannot race on the shared tool packages folder.
- Serialize concurrent NuGet source registration (`ensureNuGetSource()`) when the workspace is
  shared, so parallel invocations cannot race `dotnet new nugetconfig`.
- For the tool-install lock specifically, self-heal from a stale lock left behind by a crashed
  run, matching the SDK lock's behavior (timeout-based break with a warning), so a dead run
  cannot permanently wedge future builds on that agent's shared cache.
- Add a low-noise diagnostic (`TMPDIR`/`WORKSPACE`) to help confirm whether `dotnet`'s own
  TMPDIR-scoped locking is workspace- or agent-scoped, since that's a plausible reason it
  didn't already prevent this race.

**Non-Goals:**
- Locking the Windows (`bat`/`powershell`) paths — the observed races are unix/macOS only, and
  neither has an equivalent to a `trap`-guarded shell lock without a larger rewrite.
- Per-package/version locking for the tool-install lock. A single package/version pair for the
  same tool is already idempotent in `dotnet tool install` itself (see the existing comment
  above `installTool()`); the actual risk is cross-invocation contention on the *shared*
  packages folder, which a finer-grained lock wouldn't fully cover (a different tool's
  transitive dependency can land in the same folder).
- A single, shared lock covering both concerns. The tool-install lock and the NuGet-config lock
  protect different resources at different scopes (per-agent cache vs. per-workspace file) and
  are acquired at different points in the flow (`ensureNuGetSource()` runs before `installTool()`
  in `withInstalledDotnet()`/`withTool()`); collapsing them into one lock would over-serialize
  agent-wide work that doesn't actually contend on the same resource.
- Changing the SDK-install lock (`dotnet-sdk-provisioning`) itself — it already works and is
  out of scope here.

## Decisions

**One agent-wide lock, not per package/version.** Considered keying the lock to
`packageId`+`version` for finer-grained parallelism (letting different tools install
concurrently). Rejected: `NUGET_PACKAGES` is a single shared folder holding every tool's
*and* every transitive dependency's packages, so two different tools can still race on a
common dependency even with per-tool locks. A single lock trades some parallelism for
correctness against that broader hazard; tool installs are infrequent and fast enough
(mostly cache hits) that the serialization cost is acceptable. Confirmed with the requester.

**Reuse the exact `acquire_lock` mkdir pattern from `dotnet-install.sh`, not a new mechanism.**
`mkdir` is atomic on all POSIX filesystems, making check-and-acquire race-free without needing
`flock`/`lockfile` (unlike the pre-existing, non-atomic `Lockfile.groovy` fileExists+touch
pattern used for cache renewal, which is fine for its low-contention use case but not
appropriate to copy here). This keeps exactly one battle-tested locking idiom in the codebase
instead of a second, subtly different one.

**Lock scoped to `sh`, not to `Dotnet.groovy` state.** The lock preamble/acquire/release lives
entirely inside the generated shell script passed to `jenkins.sh(...)`, guarded by a `trap ...
EXIT INT TERM` with an explicit ownership flag (`DOTNET_TOOL_OWNS_LOCK`). This means the lock's
lifetime is exactly the `dotnet tool install` process's lifetime — released on success, failure,
or the step being killed — with no separate Groovy-side cleanup step needed, and no risk of a
waiting run deleting a lock it never acquired. The trap also echoes a "Released tool install
lock" line (mirroring the existing "Acquired tool install lock" line) so the release is visible
in the Jenkins console log, not just inferred from the lock dir's absence.

**Windows is out of scope for this change.** The race was only observed on macOS agents, and
`bat`/`powershell` have no `trap`/`mkdir`-as-mutex equivalent without a materially larger
implementation (e.g. a `.lock` file + retry loop in `.bat`, or a distinct PowerShell lock
helper). Extending coverage to Windows can be a follow-up if either race is ever observed there.

**NuGet-config lock is workspace-relative, not per-agent.** The tool-install lock lives under
the shared per-agent cache (`toolCacheDir()`) because that's what it protects. The NuGet-config
lock instead uses `./nuget.config.lock` — relative to the current working directory, exactly
like the `./nuget.config` file it protects — because that file's scope, and therefore the race,
is per-workspace, not per-agent. Using the agent-wide tool-install lock for this instead would
"work" by accident (a strict superset), but would incorrectly serialize NuGet source
registration across every job on the agent, including ones that don't share a workspace and
therefore can't actually race on `./nuget.config` — unwarranted contention for jobs that were
never at risk.

**No stale-lock timeout for the NuGet-config lock.** The tool-install lock's stale-lock
timeout/self-heal exists because it lives under the shared per-agent cache, which persists
indefinitely — an orphaned lock there would wedge every future build on that agent until
manually cleared. The NuGet-config lock lives inside the workspace instead, and a
stuck/killed build's workspace is commonly wiped wholesale (`git clean`, a fresh checkout, or
an explicit workspace-clean step) before the next build reuses it — that wipe already clears
an orphaned `./nuget.config.lock` along with everything else, so the timeout/age-tracking
machinery (the `date`/`stat` calls, the timeout env var, the stale-break branch) would be
unused complexity here. Confirmed with the requester: invest in self-healing only where a
lock can actually outlive the thing that would normally clear it.

**Duplicated lock-preamble code, not a shared generic helper.** `nugetConfigLockPreambleSh()`
mirrors `toolInstallLockPreambleSh()`'s atomic mkdir/trap shape, minus the stale-lock timeout
logic per the decision above, with its own variable names and log wording — rather than one
function parameterized by lock dir + label + "include timeout logic or not". This matches the
class's existing convention of separate `sh`/`ps`/`bat` variants over one generic templated
method (`requireDotnetCliHomeSh`/`Ps`/`Bat`) — parameterizing label text and conditional
timeout logic into the trap's single-quoted bash literal is exactly the kind of escaping-heavy
cleverness that convention avoids, and would leave one function harder to read than two short,
independently-obvious ones.

## Risks / Trade-offs

- **[Reduced parallelism]** All tool installs on an agent now serialize, even for unrelated
  tools that wouldn't actually collide. → Acceptable: installs are fast once cached (`dotnet
  tool install` is a no-op for an already-installed version), so the added wait is small
  relative to the cost of a failed build from the race.
- **[Tool-install stale lock false-positive]** A very slow (but not crashed) install could
  exceed the default 300s timeout and have its lock broken by another waiting run, causing two
  installs to run concurrently after all. → Mitigated by making the timeout configurable via
  `DOTNET_TOOL_INSTALL_LOCK_TIMEOUT`, matching the SDK lock's existing escape hatch.
- **[NuGet-config lock has no timeout]** If a workspace is somehow reused across builds without
  ever being wiped (e.g. a long-lived custom workspace that's never cleaned) and a build holding
  the lock is hard-killed, the lock could persist and wedge that workspace until it's manually
  removed. → Accepted: this is the same tradeoff already made, and evidently acceptable, for the
  pre-existing `Lockfile.groovy` cache-renewal lock in this codebase, and the requester
  explicitly preferred simplicity here over guarding against an edge case within an edge case.
- **[No Windows coverage]** Both races remain possible on Windows agents if content builds ever
  run there concurrently. → Out of scope per Non-Goals; revisit if observed.
- **[Two similar-looking lock implementations]** Having two structurally similar but
  independently-maintained lock preambles (one with a stale-lock timeout, one without) risks a
  future change to one being forgotten for the other. → Accepted per the duplication-over-
  parameterization decision above; the two are covered by parallel test cases in
  `DotnetSpec.groovy` so a regression in either is caught independently.

## Migration Plan

Purely additive to an existing shared-library step's internal implementation; no consumer-facing
API change, no rollback concerns beyond reverting the `Dotnet.groovy` change. No data migration.

## Open Questions

None — scope and locking granularity were confirmed with the requester before implementation.
