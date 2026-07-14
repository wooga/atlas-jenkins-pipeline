## Why

Content build pipelines run multiple concurrent jobs on the same Jenkins agent, each
installing a shared NuGet-packaged dotnet tool via `withDotnetTool`/`runDotnetTool`. Because
those steps redirect `NUGET_PACKAGES`/`DOTNET_CLI_HOME` to a single shared per-agent cache
directory (see the `dotnet-tool-steps` capability), two concurrent `dotnet tool install`
invocations on the same agent can race restoring the same package into that shared packages
folder. This was observed on real Jenkins runs as:

```
Unhandled exception: The process cannot access the file
'.../dotnet/tools/packages/wooga.adv5.tools.osx-arm64/0.0.388/wooga.adv5.tools.osx-arm64.0.0.388.nupkg'
because it is being used by another process.
```

`dotnet`'s own package-restore locking did not reliably prevent this. The `dotnet-sdk-provisioning`
capability already solves the equivalent problem for SDK installs with a self-healing `mkdir`
lock; tool installs need the same protection.

## What Changes

- `withDotnetTool`/`runDotnetTool` now serialize concurrent `dotnet tool install` invocations
  on unix/macOS agents using a self-healing, agent-wide `mkdir`-based lock (mirroring the
  existing SDK-install lock), so simultaneous pipeline runs on one agent no longer corrupt or
  collide on the shared tool packages folder. The lock is agent-wide (not per package/version),
  since the shared packages folder also holds transitive-dependency packages different tools
  can race on.
- A lock held past a timeout (`DOTNET_TOOL_INSTALL_LOCK_TIMEOUT`, default 300s) is treated as
  stale (e.g. left behind by a crashed run) and broken with a warning, matching the SDK lock's
  behavior.
- The Windows (`bat`) tool-install path is unchanged; this only covers the unix/macOS race that
  was actually observed.
- Adds a diagnostic log line (`TMPDIR`/`WORKSPACE`) to the tool install script, to make it
  visible in the Jenkins console whether `TMPDIR` is workspace- or agent-scoped — useful context
  since `dotnet`'s own package-extraction locking is `TMPDIR`-scoped.

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `dotnet-tool-steps`: adds a new requirement that concurrent `dotnet tool install`
  invocations on the same agent are serialized via a self-healing lock, mirroring the
  `dotnet-sdk-provisioning` capability's existing "Concurrent installs are serialized"
  requirement.

## Impact

- `src/net/wooga/jenkins/pipeline/model/Dotnet.groovy`: `installTool()`'s unix branch now runs
  through a new `toolInstallScriptSh()` helper that prepends the diagnostic echo and the lock
  preamble (`toolInstallLockPreambleSh()`/`toolInstallLockDir()`) before the existing
  `dotnet tool install` command. The Windows `bat` branch, `runTool()`, and all other steps are
  unchanged.
- `test/groovy/net/wooga/jenkins/pipeline/model/DotnetSpec.groovy`: adds coverage for the new
  lock (`withTool serializes concurrent installs with a self-healing lock`) and confirms the
  Windows path is unaffected (`withTool does not lock on Windows`).
- No changes to `vars/*.groovy`, `resources/dotnet/dotnet-install.sh`/`.ps1`, or any consumer
  pipeline's calling convention.
