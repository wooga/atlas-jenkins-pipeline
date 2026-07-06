## ADDED Requirements

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

The system SHALL provide a `runDotnetTool` shared step that provisions the .NET SDK, installs a given NuGet package as a local dotnet tool (as `withDotnetTool` does), then runs it via `dotnet tool run <toolBinary> -- <args>` with the given arguments. The `--` separator SHALL always be inserted before the forwarded arguments, so that argument values matching one of `dotnet`'s own CLI options (e.g. `--help`, `-h`) are passed through to the tool instead of being intercepted by `dotnet` itself. It SHALL accept a simple positional form (`runDotnetTool(packageId, toolBinary, args)`) and a map form supporting an optional `version` and a `returnStatus` option.

#### Scenario: Simple form installs and runs the tool

- **WHEN** a pipeline calls `runDotnetTool("MyTool", "mytool", ["--help"])`
- **THEN** `MyTool` is installed as a local dotnet tool
- **AND** `dotnet tool run mytool -- --help` is executed, so `--help` reaches the tool rather than being intercepted as a `dotnet` CLI option

#### Scenario: returnStatus controls failure behavior

- **WHEN** a pipeline calls `runDotnetTool(packageId: "MyTool", toolBinary: "mytool", args: [], returnStatus: true)` and the tool exits non-zero
- **THEN** the exit status is returned to the caller instead of failing the build

### Requirement: Tool package cache is redirected under the shared cache tree

Both `withDotnetTool` and `runDotnetTool` SHALL redirect the dotnet tool's package cache and CLI resolver state under the same shared per-agent cache directory used for the SDK itself, rather than the default `~/.nuget/packages` and `~/.dotnet` locations, by setting `NUGET_PACKAGES` and `DOTNET_CLI_HOME` for the duration of the tool install/run.

#### Scenario: Tool packages are cached under the shared cache directory

- **WHEN** either step installs a tool
- **THEN** `NUGET_PACKAGES` is set to `<cache-dir>/tools/packages` and `DOTNET_CLI_HOME` is set to `<cache-dir>/tools` for the duration of the install and any subsequent tool invocation

#### Scenario: Default NuGet/dotnet CLI locations are not touched

- **WHEN** either step installs a tool
- **THEN** the tool's package content is not written to the default `~/.nuget/packages` or `~/.dotnet` locations
