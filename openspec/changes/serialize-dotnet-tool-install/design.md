## Context

`withDotnetTool`/`runDotnetTool` redirect `NUGET_PACKAGES`/`DOTNET_CLI_HOME` to a single
per-agent shared cache (`Dotnet.groovy:nugetHomeEnv()`), so that repeated tool installs across
jobs reuse a warm cache instead of each pipeline restoring into its own throwaway location.
That sharing is exactly what caused the observed race: two concurrent `dotnet tool install`
invocations on the same agent can both try to write into the same package folder under
`NUGET_PACKAGES`. `dotnet-sdk-provisioning` already solved the equivalent problem for SDK
installs with a self-healing `mkdir` lock (`resources/dotnet/dotnet-install.sh:acquire_lock`);
this change ports the same pattern to tool installs.

## Goals / Non-Goals

**Goals:**
- Serialize concurrent `dotnet tool install` invocations on the same unix/macOS agent so they
  cannot race on the shared tool packages folder.
- Self-heal from a stale lock left behind by a crashed run, matching the SDK lock's behavior
  (timeout-based break with a warning), so a dead run cannot permanently wedge future builds.
- Add a low-noise diagnostic (`TMPDIR`/`WORKSPACE`) to help confirm whether `dotnet`'s own
  TMPDIR-scoped locking is workspace- or agent-scoped, since that's a plausible reason it
  didn't already prevent this race.

**Non-Goals:**
- Locking the Windows (`bat`) tool-install path — the observed race is unix/macOS only, and
  `bat` has no equivalent to a `trap`-guarded shell lock without a larger rewrite.
- Per-package/version locking. A single package/version pair for the same tool is already
  idempotent in `dotnet tool install` itself (see the existing comment above `installTool()`);
  the actual risk is cross-invocation contention on the *shared* packages folder, which a
  finer-grained lock wouldn't fully cover (a different tool's transitive dependency can land
  in the same folder).
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
waiting run deleting a lock it never acquired.

**Windows is out of scope for this change.** The race was only observed on macOS agents, and
`bat` has no `trap`/`mkdir`-as-mutex equivalent without a materially larger implementation
(e.g. a `.lock` file + retry loop in `.bat`, or delegating to PowerShell). Extending coverage
to Windows can be a follow-up if the race is ever observed there.

## Risks / Trade-offs

- **[Reduced parallelism]** All tool installs on an agent now serialize, even for unrelated
  tools that wouldn't actually collide. → Acceptable: installs are fast once cached (`dotnet
  tool install` is a no-op for an already-installed version), so the added wait is small
  relative to the cost of a failed build from the race.
- **[Stale lock false-positive]** A very slow (but not crashed) install could exceed the
  default 300s timeout and have its lock broken by another waiting run, causing two installs
  to run concurrently after all. → Mitigated by making the timeout configurable via
  `DOTNET_TOOL_INSTALL_LOCK_TIMEOUT`, matching the SDK lock's existing escape hatch.
- **[No Windows coverage]** The race remains possible on Windows agents if content builds ever
  run there concurrently. → Out of scope per Non-Goals; revisit if observed.

## Migration Plan

Purely additive to an existing shared-library step's internal implementation; no consumer-facing
API change, no rollback concerns beyond reverting the `Dotnet.groovy` change. No data migration.

## Open Questions

None — scope and locking granularity were confirmed with the requester before implementation.
