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

### Requirement: Automatic, overridable wooga_nuget feed registration and credentials

Both `dotnetWrapper` and `withDotnet` SHALL, by default, idempotently register the company-wide private `wooga_nuget` NuGet feed with the dotnet CLI if it is not already registered on the current agent, and SHALL bind the `artifactory_read` Jenkins credential for the duration of the command/block, exporting a `NuGetPackageSourceCredentials_<source>` environment variable in the standard `Username=<user>;Password=<pass>` NuGet CLI format so that `dotnet restore` and similar commands can authenticate against the feed without the caller wiring this up themselves. The underlying `Dotnet` model class itself has no built-in knowledge of `wooga_nuget`/`artifactory_read` — these are wooga-specific defaults applied by the step scripts (`DotnetNugetConfig`), not hardcoded into the model. Callers of the map forms of `dotnetWrapper`/`withDotnet` MAY override the NuGet source name, source URL, and/or credentials ID, or opt out of NuGet setup entirely with `nuget: false`. The string form of `dotnetWrapper` has no place for these keys and always applies the defaults.

#### Scenario: NuGet feed is registered on first use on an agent

- **WHEN** `withDotnet` or `dotnetWrapper` runs on an agent where the `wooga_nuget` feed is not yet registered
- **THEN** the feed is registered with the dotnet CLI before the block/command runs

#### Scenario: NuGet feed registration is idempotent

- **WHEN** `withDotnet` or `dotnetWrapper` runs on an agent where the `wooga_nuget` feed is already registered (e.g. from a prior build)
- **THEN** no attempt is made to re-register it, and no error occurs from the feed already existing

#### Scenario: NuGet credentials are exported for the duration of the block/command

- **WHEN** `withDotnet` or `dotnetWrapper` runs
- **THEN** the `artifactory_read` credential is bound and `NuGetPackageSourceCredentials_wooga_nuget` is set to `Username=<bound username>;Password=<bound password>` for the duration of the block/command only

#### Scenario: NuGet source and credentials can be overridden

- **WHEN** a caller passes `nugetSourceName`, `nugetSourceUrl`, and/or `nugetCredentialsId` to the map form of `withDotnet` or `dotnetWrapper`
- **THEN** the given values are used instead of the `wooga_nuget`/`artifactory_read` defaults for that invocation

#### Scenario: NuGet setup can be fully disabled

- **WHEN** a caller passes `nuget: false` to the map form of `withDotnet` or `dotnetWrapper`
- **THEN** no NuGet feed is registered and no credentials are bound for that invocation

### Requirement: dotnet CLI state never touches the user's default locations

`dotnetWrapper` and `withDotnet` SHALL unconditionally redirect `NUGET_PACKAGES` and `DOTNET_CLI_HOME` under the same shared per-agent cache directory used for the SDK itself, for every invocation, regardless of whether a NuGet source is configured. This SHALL hold for the SDK-only path as well as the tool path (`withDotnetTool`/`runDotnetTool`) — there is exactly one `DOTNET_CLI_HOME` scope per invocation, applied before any `dotnet` command (including NuGet feed registration) runs. Nothing this library does with `dotnet` SHALL ever write to the user's default `~/.nuget` or `~/.dotnet` locations.

#### Scenario: NUGET_PACKAGES/DOTNET_CLI_HOME are redirected even with no NuGet config

- **WHEN** a pipeline calls `withDotnet(nuget: false) { sh "dotnet build" }` or `dotnetWrapper(command: "build", nuget: false)`
- **THEN** `NUGET_PACKAGES` and `DOTNET_CLI_HOME` are still set to locations under the shared cache directory for the duration of the block/command

#### Scenario: dotnet CLI invocations fail fast if DOTNET_CLI_HOME is ever unset

- **WHEN** any internal `dotnet` invocation this library makes (NuGet feed registration, tool install, tool run) somehow runs without `DOTNET_CLI_HOME` set
- **THEN** it fails immediately with a clear error instead of silently writing to the user's default NuGet/dotnet locations
