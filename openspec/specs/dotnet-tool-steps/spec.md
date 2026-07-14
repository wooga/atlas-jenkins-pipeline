## Purpose

TBD - created by archiving change add-dotnet-steps. Update Purpose after archive.

## Requirements

### Requirement: withDotnetTool block step

The system SHALL provide a `withDotnetTool` shared step that provisions the .NET SDK (as `withDotnet` does, including the shared NuGet feed and credentials), installs a given NuGet package as a local (manifest-based) dotnet tool in the current workspace, and runs a caller-provided block with the tool invocable via `dotnet tool run <toolBinary>`. It SHALL accept a positional-package-id form (`withDotnetTool("pkg") { ... }`) and a map form (`withDotnetTool(packageId: "pkg", version: "1.2.3") { ... }`) for pinning an optional exact tool version.

#### Scenario: Tool is installed then the block runs

- **WHEN** a pipeline calls `withDotnetTool("MyTool") { sh "dotnet tool run mytool" }`
- **THEN** the .NET SDK is provisioned
- **AND** `MyTool` is installed as a local dotnet tool, creating a tool manifest if none exists
- **AND** the block runs with the tool available via `dotnet tool run mytool`

#### Scenario: Explicit tool version is pinned

- **WHEN** a pipeline calls `withDotnetTool(packageId: "MyTool", version: "1.2.3") { ... }`
- **THEN** `MyTool` is installed at exactly version `1.2.3`, passing `--allow-downgrade` so the install succeeds even if a different version was already present in the workspace's tool manifest

### Requirement: runDotnetTool command step

The system SHALL provide a `runDotnetTool` shared step that provisions the .NET SDK, installs a given NuGet package as a local dotnet tool (as `withDotnetTool` does), then runs it via `dotnet tool run <toolBinary> -- <args>` with the given arguments. The `--` separator SHALL always be inserted before the forwarded arguments, so that argument values matching one of `dotnet`'s own CLI options (e.g. `--help`, `-h`) are passed through to the tool instead of being intercepted by `dotnet` itself. It SHALL accept a simple positional form (`runDotnetTool(packageId, toolBinary, args)`) and a map form supporting an optional `version`, a `returnStatus` option, and unix-only `loginShell`/`umask`/`logCommandToStdErr` options.

#### Scenario: Simple form installs and runs the tool

- **WHEN** a pipeline calls `runDotnetTool("MyTool", "mytool", ["--help"])`
- **THEN** `MyTool` is installed as a local dotnet tool
- **AND** `dotnet tool run mytool -- --help` is executed, so `--help` reaches the tool rather than being intercepted as a `dotnet` CLI option

#### Scenario: returnStatus controls failure behavior

- **WHEN** a pipeline calls `runDotnetTool(packageId: "MyTool", toolBinary: "mytool", args: [], returnStatus: true)` and the tool exits non-zero
- **THEN** the exit status is returned to the caller instead of failing the build

#### Scenario: loginShell, umask, and logCommandToStdErr customize the unix invocation

- **WHEN** a pipeline calls `runDotnetTool(packageId: "MyTool", toolBinary: "mytool", args: ["arg"], loginShell: true, umask: "002", logCommandToStdErr: true)` on a unix agent
- **THEN** the tool is run via a script beginning with a `#!/bin/bash -l` shebang, followed by `export PATH="$DOTNET_ROOT:$PATH"`, followed by `set -x`, followed by `umask 002`, followed by the `dotnet tool run` command
- **AND** on a Windows agent, these three options have no effect on the generated `bat` script

#### Scenario: loginShell defends against a login shell overwriting PATH

- **WHEN** a pipeline calls `runDotnetTool(..., loginShell: true)` on an agent whose `/etc/profile`/`~/.bash_profile` unconditionally overwrites `PATH`
- **THEN** the tool script still resolves `dotnet` correctly, because `export PATH="$DOTNET_ROOT:$PATH"` is re-added immediately after the shebang, restoring the cache dir on `PATH` before the `dotnet tool run` command executes

### Requirement: Tool package cache is redirected under the shared cache tree

Both `withDotnetTool` and `runDotnetTool` SHALL redirect the dotnet tool's package cache and CLI resolver state under the same shared per-agent cache directory used for the SDK itself, rather than the default `~/.nuget/packages` and `~/.dotnet` locations, by setting `NUGET_PACKAGES` and `DOTNET_CLI_HOME` for the duration of the tool install/run.

#### Scenario: Tool packages are cached under the shared cache directory

- **WHEN** either step installs a tool
- **THEN** `NUGET_PACKAGES` is set to `<cache-dir>/tools/packages` and `DOTNET_CLI_HOME` is set to `<cache-dir>/tools` for the duration of the install and any subsequent tool invocation

#### Scenario: Default NuGet/dotnet CLI locations are not touched

- **WHEN** either step installs a tool
- **THEN** the tool's package content is not written to the default `~/.nuget/packages` or `~/.dotnet` locations

### Requirement: The configured NuGet feed SHALL be visible to dotnet tool install/run

`DOTNET_CLI_HOME` governs where `dotnet` reads and writes its user-level `NuGet.Config`, independently of the real `$HOME`. `withDotnetTool`/`runDotnetTool` SHALL use the exact same `DOTNET_CLI_HOME` scope for NuGet feed registration and for tool install/run, since `withDotnet`/`dotnetWrapper` (which `withDotnetTool`/`runDotnetTool` build on) now redirect `DOTNET_CLI_HOME` unconditionally (see the `dotnet-pipeline-steps` capability) — the NuGet source registered while provisioning SHALL therefore already be the one `dotnet tool install`/`dotnet tool run` consult, with no separate re-registration step needed.

#### Scenario: A configured NuGet source is visible to dotnet tool install

- **WHEN** `withDotnetTool`/`runDotnetTool` is configured with a NuGet source (directly, or via the org-wide default applied by the `vars/*.groovy` step)
- **THEN** `dotnet tool install` resolves the package from that source, not only the default `nuget.org` feed

#### Scenario: No NuGet source registration when none is configured

- **WHEN** `withTool`/`runTool` is used with no NuGet source configured at all
- **THEN** no NuGet source registration is attempted

### Requirement: Concurrent tool installs are serialized

The system SHALL serialize concurrent `dotnet tool install` invocations targeting the shared
per-agent tool cache using a self-healing lock, so that simultaneous pipeline runs on the same
agent do not corrupt or collide on the shared `NUGET_PACKAGES` packages folder. The lock SHALL
be agent-wide (not scoped to a single package/version), since the shared packages folder also
holds transitive-dependency packages that different tools can race on. A lock held beyond a
configurable timeout SHALL be treated as stale and broken with a warning. This requirement
applies to unix/macOS agents only; the Windows (`bat`) tool-install path is unaffected.

#### Scenario: Two tool installs race for the same shared tool cache

- **WHEN** two pipeline runs on the same unix/macOS agent both call `withDotnetTool`/`runDotnetTool`
  at the same time
- **THEN** one run acquires the tool install lock and installs while the other waits
- **AND** the waiting run proceeds with its own install once the lock is released

#### Scenario: Stale tool install lock recovery

- **WHEN** a tool install lock has been held longer than the configured timeout (default 300s,
  overridable via `DOTNET_TOOL_INSTALL_LOCK_TIMEOUT`) because a previous run crashed
- **THEN** the lock is broken with a warning and the tool install continues

### Requirement: Concurrent NuGet source registration is serialized

The system SHALL serialize concurrent NuGet source registration (the check-then-create-then-add
sequence that ensures the configured feed is registered in a workspace-relative `./nuget.config`,
creating that file via `dotnet new nugetconfig` if it doesn't already exist) using a self-healing
lock, so that simultaneous invocations sharing the same workspace (e.g. parallel stages of the
same job) do not race `dotnet new nugetconfig`, which fails if the file already exists. The lock
SHALL be scoped to the workspace (not the per-agent tool cache), since the resource it protects —
the workspace-relative `./nuget.config` — is itself workspace-scoped. A lock held beyond a
configurable timeout SHALL be treated as stale and broken. This requirement applies to unix/macOS
agents only; the Windows (`powershell`) NuGet-source-registration path is unaffected.

#### Scenario: Two invocations race to register the NuGet source in a shared workspace

- **WHEN** two invocations sharing the same workspace on the same unix/macOS agent both need to
  register the configured NuGet source at the same time
- **THEN** one acquires the NuGet config lock and completes the check-then-create-then-add
  sequence while the other waits
- **AND** the waiting invocation proceeds once the lock is released, finding the source already
  registered

#### Scenario: Stale NuGet config lock recovery

- **WHEN** a NuGet config lock has been held longer than the configured timeout (default 300s,
  overridable via `DOTNET_NUGET_CONFIG_LOCK_TIMEOUT`) because a previous run crashed
- **THEN** the lock is broken and NuGet source registration continues
