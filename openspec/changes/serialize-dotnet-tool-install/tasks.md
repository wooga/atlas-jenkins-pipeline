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
