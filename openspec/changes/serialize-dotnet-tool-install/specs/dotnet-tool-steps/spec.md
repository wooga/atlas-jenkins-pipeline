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
