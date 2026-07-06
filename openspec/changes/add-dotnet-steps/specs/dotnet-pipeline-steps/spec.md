## ADDED Requirements

### Requirement: dotnetWrapper command step

The system SHALL provide a `dotnetWrapper` shared step that provisions the requested .NET SDK and then runs `dotnet <command>` against it. It SHALL accept a string form (`dotnetWrapper "build"`) and a map form (`dotnetWrapper(command: "build", channel: "8.0")`). The map form SHALL accept the optional selectors `version`, `channel`, and `globalJson`, and SHALL support `returnStatus` and `returnStdout` options mirroring `gradleWrapper`. The command SHALL execute via `sh` on unix agents and `bat` on Windows agents.

#### Scenario: String form runs a dotnet command

- **WHEN** a pipeline calls `dotnetWrapper "build --configuration Release"`
- **THEN** the SDK is provisioned using auto-detection (workspace `global.json`, else the org-wide default version)
- **AND** `dotnet build --configuration Release` is executed against the provisioned SDK

#### Scenario: Map form with an explicit selector

- **WHEN** a pipeline calls `dotnetWrapper(command: "test", channel: "8.0")`
- **THEN** the `8.0` channel SDK is provisioned
- **AND** `dotnet test` is executed against it

#### Scenario: Returning command status

- **WHEN** a pipeline calls `dotnetWrapper(command: "test", returnStatus: true)`
- **THEN** the exit status of the `dotnet test` invocation is returned to the caller instead of failing the build on a non-zero exit

#### Scenario: Command runs with the correct shell per OS

- **WHEN** `dotnetWrapper` executes a command on a unix agent
- **THEN** the command runs via `sh`
- **AND WHEN** it executes on a Windows agent it runs via `bat`

### Requirement: withDotnet block step

The system SHALL provide a `withDotnet` shared step that provisions the requested .NET SDK and executes a caller-provided block with the cached `dotnet` available on `PATH` and `DOTNET_ROOT` set to the cache directory. It SHALL accept an optional config map (`withDotnet(version: "8.0.401") { ... }`) with the selectors `version`, `channel`, and `globalJson`, and SHALL support a no-config form (`withDotnet { ... }`).

#### Scenario: Block runs with dotnet on PATH

- **WHEN** a pipeline calls `withDotnet { sh "dotnet build" }`
- **THEN** the SDK is provisioned using auto-detection (workspace `global.json`, else the org-wide default version)
- **AND** the block executes with the cache directory prepended to `PATH` and `DOTNET_ROOT` pointing at the cache directory
- **AND** the `dotnet` resolved inside the block is the cached SDK, not any system-installed copy

#### Scenario: Block with an explicit selector

- **WHEN** a pipeline calls `withDotnet(version: "8.0.401") { sh "dotnet --version" }`
- **THEN** SDK `8.0.401` is provisioned
- **AND** the block runs with that SDK available

#### Scenario: Environment is scoped to the block

- **WHEN** the `withDotnet` block completes
- **THEN** the `PATH` and `DOTNET_ROOT` modifications apply only for the duration of the block

### Requirement: Both steps report the SDK in use

Each invocation of `dotnetWrapper` or `withDotnet` SHALL surface in the build log which .NET SDK version is being used and where it was resolved from, consistent with the provisioning logging behavior.

#### Scenario: dotnetWrapper logs the SDK

- **WHEN** `dotnetWrapper` provisions and runs a command
- **THEN** the build log shows the resolved SDK version, the selector source, and cache hit/miss before the command runs

#### Scenario: withDotnet logs the SDK

- **WHEN** `withDotnet` provisions and enters the block
- **THEN** the build log shows the resolved SDK version, the selector source, and cache hit/miss before the block runs

### Requirement: Automatic wooga_nuget feed registration and credentials

Both `dotnetWrapper` and `withDotnet` SHALL, as part of provisioning, idempotently register the company-wide private `wooga_nuget` NuGet feed with the dotnet CLI if it is not already registered on the current agent, and SHALL bind the `artifactory_read` Jenkins credential for the duration of the command/block, exporting a `NuGetPackageSourceCredentials_wooga_nuget` environment variable in the standard `Username=<user>;Password=<pass>` NuGet CLI format so that `dotnet restore` and similar commands can authenticate against the feed without the caller wiring this up themselves.

#### Scenario: NuGet feed is registered on first use on an agent

- **WHEN** `withDotnet` or `dotnetWrapper` runs on an agent where the `wooga_nuget` feed is not yet registered
- **THEN** the feed is registered with the dotnet CLI before the block/command runs

#### Scenario: NuGet feed registration is idempotent

- **WHEN** `withDotnet` or `dotnetWrapper` runs on an agent where the `wooga_nuget` feed is already registered (e.g. from a prior build)
- **THEN** no attempt is made to re-register it, and no error occurs from the feed already existing

#### Scenario: NuGet credentials are exported for the duration of the block/command

- **WHEN** `withDotnet` or `dotnetWrapper` runs
- **THEN** the `artifactory_read` credential is bound and `NuGetPackageSourceCredentials_wooga_nuget` is set to `Username=<bound username>;Password=<bound password>` for the duration of the block/command only
