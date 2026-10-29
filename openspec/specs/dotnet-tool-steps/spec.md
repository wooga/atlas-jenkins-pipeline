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
timeout SHALL be treated as stale and broken. This requirement applies to unix/macOS agents
only; the Windows (`bat`) tool-install path is unaffected.

#### Scenario: Two tool installs race for the same shared tool cache

- **WHEN** two pipeline runs on the same unix/macOS agent both call `withDotnetTool`/`runDotnetTool`
  at the same time
- **THEN** one run acquires the tool install lock and installs while the other waits
- **AND** the waiting run proceeds with its own install once the lock is released

#### Scenario: Stale tool install lock recovery

- **WHEN** a tool install lock has been held longer than the lock's timeout because a previous
  run crashed
- **THEN** the lock is broken and the tool install continues

### Requirement: Concurrent NuGet source registration is serialized

The system SHALL serialize concurrent NuGet source registration (the check-then-create-then-add
sequence that ensures the configured feed is registered in a workspace-relative `./nuget.config`,
creating that file via `dotnet new nugetconfig` if it doesn't already exist) using a self-healing
lock, so that simultaneous invocations sharing the same workspace (e.g. parallel stages of the
same job) do not race `dotnet new nugetconfig`, which fails if the file already exists. The lock
SHALL be scoped to the workspace (not the per-agent tool cache), since the resource it protects —
the workspace-relative `./nuget.config` — is itself workspace-scoped. A lock held beyond a
timeout SHALL be treated as stale and broken. This requirement applies to unix/macOS agents
only; the Windows (`powershell`) NuGet-source-registration path is unaffected.

#### Scenario: Two invocations race to register the NuGet source in a shared workspace

- **WHEN** two invocations sharing the same workspace on the same unix/macOS agent both need to
  register the configured NuGet source at the same time
- **THEN** one acquires the NuGet config lock and completes the check-then-create-then-add
  sequence while the other waits
- **AND** the waiting invocation proceeds once the lock is released, finding the source already
  registered

#### Scenario: Stale NuGet config lock recovery

- **WHEN** a NuGet config lock has been held longer than the lock's timeout because a previous
  run crashed
- **THEN** the lock is broken and NuGet source registration continues

### Requirement: Tool stdout and stderr can each be duplicated into a caller-named file

The system SHALL provide `stdoutFile` and `stderrFile` options on `runDotnetTool`/`Dotnet.runTool`
that each name a workspace-relative file the corresponding stream is duplicated into (while still
passing that stream through live to the console). Either may be given independently of the other,
and an unrequested stream SHALL be left untouched. The files belong to the caller: the system SHALL
NOT read them, and SHALL NOT remove them, so that a later step or a `post` block can read them back
with `readFile` after the call - including after the tool failed. Passing either option SHALL NOT
change the step's own return/throw contract, which continues to be governed solely by
`returnStatus`. The system makes no requirement on how a given tool splits its own output between
the two streams - it duplicates whatever each requested stream already contains. This requirement
applies to unix/macOS agents only.

#### Scenario: Output still streams live to the console while being duplicated

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log")` against a tool that prints
  output with a delay between lines
- **THEN** each line appears in the live Jenkins console log as the tool prints it, not all at
  once only after the tool finishes

#### Scenario: Duplicating a failing tool's output

- **WHEN** a pipeline calls `runDotnetTool(packageId: "MyTool", toolBinary: "mytool", args: [...], stdoutFile: "out.log", stderrFile: "err.log")`
  and the tool exits non-zero while printing to both stdout and stderr
- **THEN** `out.log` contains exactly what the tool printed to stdout
- **AND** `err.log` contains exactly what the tool printed to stderr
- **AND** the call still fails the build on the tool's non-zero exit, exactly as it would without
  either option

#### Scenario: A caller that wants the exit code instead of a failure

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log", returnStatus: true)` and the
  tool exits non-zero
- **THEN** the call returns the tool's exit status without throwing
- **AND** `err.log` still contains what the tool printed to stderr

#### Scenario: Capturing one stream leaves the other untouched

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log")` without `stdoutFile`
- **THEN** `err.log` contains what the tool printed to stderr
- **AND** the tool's stdout reaches the console exactly as it would without either option, with no
  file created for it

#### Scenario: A tool that never writes to the captured stream

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log")` against a tool that prints
  everything to stdout and never writes to stderr
- **THEN** `err.log` exists and is empty, which the caller can treat as "nothing to report"

#### Scenario: Capture files survive the call so a post block can read them

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log")` and the tool exits non-zero,
  failing the stage
- **THEN** `err.log` still exists after the call, with its contents intact
- **AND** a `post` block on that stage can read it

#### Scenario: A leftover file from an earlier build is not read back as this run's output

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log")` on a reused workspace where
  a file already exists at that path from an earlier build, and this run exits before the tool's
  command ever runs (e.g. a missing `DOTNET_CLI_HOME`, or a failure setting up the capture
  mechanism)
- **THEN** the file the caller reads back is empty, not the earlier build's content

#### Scenario: An unsafe capture path is rejected rather than altered

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: <a path containing a quote, `$`, a
  backtick, a backslash, or a newline>)`
- **THEN** the call fails immediately with a clear error naming the offending option
- **AND** the path is not silently rewritten into a different one, since the caller will read back
  the exact path it named

#### Scenario: A capture path outside the workspace is rejected

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: <an absolute path, or one containing a
  `..` segment>)`
- **THEN** the call fails immediately with a clear error, since such a file could be written but
  not read back via `readFile`

#### Scenario: Naming one file for both streams is rejected

- **WHEN** a pipeline calls `runDotnetTool(..., stdoutFile: "both.log", stderrFile: "both.log")`
- **THEN** the call fails immediately with a clear error, rather than interleaving the two streams
  into one file unpredictably

#### Scenario: Capture files are unsupported on Windows

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log")` on a Windows agent
- **THEN** the call fails immediately with a clear error, rather than silently producing no file
  for a caller that is about to read one

#### Scenario: A cleanup failure does not mask a real failure from the capturing call itself

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log")` and the capturing call
  itself fails (the tool's own non-zero exit, or an unrelated reason such as an agent disconnect),
  and the subsequent internal cleanup also fails
- **THEN** the original failure propagates to the caller
- **AND** the cleanup failure is not what the caller sees

#### Scenario: A hung/lingering child of the tool does not block the call forever

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log")` against a tool that exits
  but leaves a child process running that still holds the inherited stdout/stderr open
- **THEN** the call still returns, rather than blocking indefinitely
- **AND** the capture file contains whatever was written before the wait was given up on, which may
  be truncated relative to what the lingering child eventually would have produced

#### Scenario: A stale artifact from a previous run at the same internal path does not silently break capture

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log")` and a file already exists at
  a path this call would use to set up its capture mechanism, left behind by a previous crashed or
  killed run
- **THEN** the stale artifact does not cause this call to silently capture nothing while still
  reporting an unremarkable exit code

#### Scenario: A failure setting up the capture mechanism itself is reported distinguishably

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log")` and setting up the capture
  mechanism fails for a reason unrelated to the tool itself (e.g. the agent's disk is full or
  permissions prevent creating a file at the capture path)
- **THEN** the call reports a distinguishable failure, rather than proceeding into an unrelated
  cascade of failures that would otherwise look the same as the tool itself failing

#### Scenario: The tool does not retain access to the saved console file descriptors

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log")`
- **THEN** neither the tool's own process nor any child it spawns has access to the file
  descriptors this mechanism uses internally to pass output through to the live console

