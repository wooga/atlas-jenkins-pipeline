## ADDED Requirements

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
creating that file via `dotnet new nugetconfig` if it doesn't already exist) using a lock, so
that simultaneous invocations sharing the same workspace (e.g. parallel stages of the same job)
do not race `dotnet new nugetconfig`, which fails if the file already exists. The lock SHALL be
scoped to the workspace (not the per-agent tool cache), since the resource it protects — the
workspace-relative `./nuget.config` — is itself workspace-scoped. Unlike the tool-install lock,
this lock has no stale-lock timeout/self-heal mechanism: a workspace is commonly wiped wholesale
after a stuck or killed build, which already clears an orphaned lock along with everything else,
so a lock left behind by a crash is expected to be cleared by that workspace cleanup rather than
by a timeout. This requirement applies to unix/macOS agents only; the Windows (`powershell`)
NuGet-source-registration path is unaffected.

#### Scenario: Two invocations race to register the NuGet source in a shared workspace

- **WHEN** two invocations sharing the same workspace on the same unix/macOS agent both need to
  register the configured NuGet source at the same time
- **THEN** one acquires the NuGet config lock and completes the check-then-create-then-add
  sequence while the other waits
- **AND** the waiting invocation proceeds once the lock is released, finding the source already
  registered
