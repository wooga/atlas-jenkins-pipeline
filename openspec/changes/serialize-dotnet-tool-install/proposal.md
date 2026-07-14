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

A second, related race surfaced during real-Jenkins verification of the first fix: parallel
stages of the *same* job sharing one workspace both call into `ensureNuGetSource()`, which
registers the org's NuGet feed into a workspace-relative `./nuget.config`, creating it via
`dotnet new nugetconfig` if it doesn't already exist. Two concurrent runs can both see the file
missing and both attempt to create it; the loser fails, since `dotnet new nugetconfig` refuses
to overwrite an existing file:

```
Creating this template will make changes to existing files:
  Overwrite   ./nuget.config
To create the template anyway, run the command with '--force' option:
   dotnet new nugetconfig --force
For details on the exit code, refer to https://aka.ms/templating-exit-codes#73
script returned exit code 73
```

This is the same class of check-then-act race as the tool-install one, just on a different,
workspace-scoped resource, so it's fixed the same way.

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
- `ensureNuGetSource()`'s check-then-create-then-add sequence (list existing sources, create
  `./nuget.config` if missing, add the source) is now similarly serialized on unix/macOS agents,
  using a self-healing, workspace-relative `mkdir`-based lock (`./nuget.config.lock`, sibling to
  the file it protects) with its own timeout override (`DOTNET_NUGET_CONFIG_LOCK_TIMEOUT`,
  default 300s), so parallel stages/pipelines sharing a workspace no longer race
  `dotnet new nugetconfig`.
- Both new locks release a `Released <lock name> lock` log line from the same `trap` that
  removes the lock directory, so the full acquire/release lifecycle is visible in the Jenkins
  console log (not just "Acquired").
- Both locks cover unix/macOS agents only; the Windows (`bat`/`powershell`) paths for tool
  install and NuGet source registration are unchanged — these are the races actually observed.
- Adds a diagnostic log line (`TMPDIR`/`WORKSPACE`) to the tool install script, to make it
  visible in the Jenkins console whether `TMPDIR` is workspace- or agent-scoped — useful context
  since `dotnet`'s own package-extraction locking is `TMPDIR`-scoped.

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `dotnet-tool-steps`: adds two new requirements — concurrent `dotnet tool install`
  invocations on the same agent are serialized via a self-healing, agent-wide lock (mirroring
  the `dotnet-sdk-provisioning` capability's existing "Concurrent installs are serialized"
  requirement), and concurrent NuGet source registration into a shared workspace is serialized
  via a self-healing, workspace-relative lock.

## Impact

- `src/net/wooga/jenkins/pipeline/model/Dotnet.groovy`:
  - `installTool()`'s unix branch now runs through a new `toolInstallScriptSh()` helper that
    prepends the diagnostic echo and the lock preamble (`toolInstallLockPreambleSh()`/
    `toolInstallLockDir()`) before the existing `dotnet tool install` command.
  - `ensureNuGetSource()`'s unix branch now runs through a new `ensureNuGetSourceScriptSh()`
    helper that prepends its own lock preamble (`nugetConfigLockPreambleSh()`/
    `nugetConfigLockDir()`) before the existing check-then-create-then-add sequence.
  - The Windows `bat`/`powershell` branches, `runTool()`, and all other steps are unchanged.
- `test/groovy/net/wooga/jenkins/pipeline/model/DotnetSpec.groovy`: adds coverage for both new
  locks (`withTool serializes concurrent installs with a self-healing lock`,
  `withInstalledDotnet serializes concurrent NuGet source registration with a self-healing
  lock`) and confirms the Windows paths are unaffected (`withTool does not lock on Windows`,
  and an added assertion in the existing Windows NuGet-source test).
- No changes to `vars/*.groovy`, `resources/dotnet/dotnet-install.sh`/`.ps1`, or any consumer
  pipeline's calling convention.
