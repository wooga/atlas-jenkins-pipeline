## 1. Lock implementation in Dotnet.groovy

- [x] 1.1 Add `toolInstallLockDir()`, returning a lock dir sibling to `toolCacheDir()`
      (`<cache-dir>/tools.tool-install.lock`), distinct from the SDK's `.install.lock`.
- [x] 1.2 Add `toolInstallLockPreambleSh(lockDir)`: a POSIX-`sh`-safe, atomic `mkdir`-based
      lock preamble mirroring `dotnet-install.sh`'s `acquire_lock()` — stale-lock timeout via
      `DOTNET_TOOL_INSTALL_LOCK_TIMEOUT` (default 300s), break-with-warning on staleness, and
      an explicit ownership flag guarding the `trap ... EXIT INT TERM` release so a run killed
      while still waiting never deletes a lock it doesn't own.
- [x] 1.3 Add `toolInstallScriptSh(command)`: assembles the `DOTNET_CLI_HOME` guard, a
      `TMPDIR`/`WORKSPACE` diagnostic echo, the lock preamble, then the install command.
- [x] 1.4 Wire `installTool()`'s unix branch to `jenkins.sh(label: command, script:
      toolInstallScriptSh(command))`; leave the Windows `bat` branch unchanged.
- [x] 1.5 Have the lock's `trap` also echo a `Released tool install lock` line (mirroring the
      existing `Acquired tool install lock` line), so the full acquire/release lifecycle is
      visible in the console log while verifying the fix on real Jenkins.

## 2. Tests

- [x] 2.1 Add `DotnetSpec`: "withTool serializes concurrent installs with a self-healing
      lock" — asserts the generated sh script contains the lock dir path, the timeout default,
      the stale-lock break, and the ownership-guarded trap.
- [x] 2.2 Add `DotnetSpec`: "withTool does not lock on Windows" — asserts the bat script has
      no lock preamble.
- [x] 2.3 Run the full `DotnetSpec` suite (and the full test suite) to confirm no regressions
      in existing `withTool`/`runTool`/Windows-bat coverage.

## 3. OpenSpec change artifacts

- [x] 3.1 `proposal.md` — why/what/capabilities/impact.
- [x] 3.2 `design.md` — lock-scope and unix-only decisions with rationale.
- [x] 3.3 `specs/dotnet-tool-steps/spec.md` delta — ADDED requirement "Concurrent tool installs
      are serialized" with race and stale-lock-recovery scenarios.
- [x] 3.4 Validate the change (`openspec validate serialize-dotnet-tool-install`) before
      archiving.

## 4. Verification (real Jenkins)

- [ ] 4.1 Trigger two concurrent content-pipeline builds on the same agent installing the same
      tool; confirm the `.nupkg ... used by another process` error no longer occurs and the
      console shows one run acquiring the lock while the other waits.
- [ ] 4.2 Confirm the `[dotnet] TMPDIR='...' WORKSPACE='...'` diagnostic line appears and
      reveals whether `TMPDIR` is workspace-scoped or agent-global.
- [ ] 4.3 Leave a stale `tools.tool-install.lock` dir behind, lower
      `DOTNET_TOOL_INSTALL_LOCK_TIMEOUT`, and confirm the next install breaks it with a warning
      and proceeds.

## 5. NuGet source registration lock (found during real-Jenkins verification of section 4)

Real testing surfaced a second, structurally identical race: parallel stages of the same job
sharing one workspace both called `ensureNuGetSource()`, which raced `dotnet new nugetconfig`
and failed with exit code 73 ("Overwrite ./nuget.config ... run with '--force'").

- [x] 5.1 Add `nugetConfigLockDir()`, returning a workspace-relative lock dir
      (`./nuget.config.lock`, sibling to the file it protects) — distinct from the agent-wide
      `toolInstallLockDir()`, since the contended resource here is workspace-scoped.
- [x] 5.2 Add `nugetConfigLockPreambleSh(lockDir)`: the same atomic `mkdir`-based, self-healing
      lock shape as `toolInstallLockPreambleSh()`, with its own variable names, its own timeout
      override (`DOTNET_NUGET_CONFIG_LOCK_TIMEOUT`, default 300s), and its own acquire/release
      log wording.
- [x] 5.3 Add `ensureNuGetSourceScriptSh(addSourceCommand)`: assembles the `DOTNET_CLI_HOME`
      guard, the existing "Ensuring NuGet source..." echo, the new lock preamble, then the
      existing check-then-create-then-add sequence unchanged.
- [x] 5.4 Wire `ensureNuGetSource()`'s unix branch to
      `jenkins.sh(label: addSourceCommand, script: ensureNuGetSourceScriptSh(addSourceCommand))`;
      leave the Windows `powershell` branch unchanged.
- [x] 5.5 Add `DotnetSpec`: "withInstalledDotnet serializes concurrent NuGet source
      registration with a self-healing lock" — asserts the lock dir, timeout default, stale-lock
      break, ownership-guarded trap, and that the lock wraps the whole check-then-create-then-add
      sequence (not just part of it).
- [x] 5.6 Strengthen the existing "withInstalledDotnet registers the NuGet source via powershell
      on Windows" test with an assertion that the Windows script has no lock preamble.
- [x] 5.7 Update `proposal.md`, `design.md`, and the `specs/dotnet-tool-steps/spec.md` delta to
      cover the new "Concurrent NuGet source registration is serialized" requirement; re-validate
      the change.
- [ ] 5.8 Verify on real Jenkins: reproduce the original parallel-stages-sharing-a-workspace
      scenario and confirm the `exit code 73` failure no longer occurs, with
      `Acquired`/`Released NuGet config lock` visible in the console log for the two runs.
