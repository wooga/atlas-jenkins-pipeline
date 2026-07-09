## ADDED Requirements

### Requirement: Cross-platform SDK provisioning via official install scripts

The system SHALL provision a .NET SDK on the current agent using Microsoft's official `dotnet-install` scripts, selecting the POSIX script (`dotnet-install.sh`) on unix agents and the PowerShell script (`dotnet-install.ps1`) on Windows agents. The official install script SHALL be obtained at runtime rather than reimplemented.

#### Scenario: Provisioning on a unix agent

- **WHEN** SDK provisioning runs on an agent where `isUnix()` is true
- **THEN** the bundled POSIX wrapper (`resources/dotnet/dotnet-install.sh`) is written to the workspace and executed
- **AND** the wrapper downloads Microsoft's official `dotnet-install.sh` at runtime to perform the install

#### Scenario: Provisioning on a Windows agent

- **WHEN** SDK provisioning runs on an agent where `isUnix()` is false
- **THEN** the bundled PowerShell wrapper (`resources/dotnet/dotnet-install.ps1`) is written to the workspace and executed
- **AND** the wrapper downloads Microsoft's official `dotnet-install.ps1` at runtime to perform the install

### Requirement: SDK selector resolution

The system SHALL determine which SDK to install from at most one caller-provided selector — an exact `version`, a `channel`, or a path to a `global.json` (`globalJson`) — applying the following precedence: `version`, then `channel`, then `globalJson`. When no selector is provided, the system SHALL auto-detect a `global.json` in the workspace root and use it if present. When no selector is provided and no `global.json` is found, the system SHALL install a pinned, exact, org-wide default SDK version (not a floating channel).

#### Scenario: Explicit version selector

- **WHEN** a caller provides `version: "8.0.401"`
- **THEN** the official install script is invoked to install exactly SDK version `8.0.401`

#### Scenario: Explicit channel selector

- **WHEN** a caller provides `channel: "8.0"`
- **THEN** the official install script is invoked with channel `8.0`

#### Scenario: Explicit global.json selector

- **WHEN** a caller provides `globalJson: "path/to/global.json"`
- **THEN** the `sdk.version` value is read from that file and its `major.minor` is used as a floating install channel (tracking the latest patch in that channel, matching GitHub Actions' `setup-dotnet` behavior for `global.json`)

#### Scenario: Auto-detected global.json

- **WHEN** no selector is provided and a `global.json` exists in the workspace root
- **THEN** the `sdk.version` from the workspace `global.json` is used to derive a floating install channel, same as the explicit global.json selector

#### Scenario: No selector and no global.json

- **WHEN** no selector is provided and no `global.json` is found in the workspace root
- **THEN** the pinned, exact org-wide default SDK version is installed
- **AND** this default is NOT a floating channel or "latest" resolution — it stays the same across builds until the org-wide default itself is deliberately changed

### Requirement: Conflicting selectors are rejected

The system SHALL fail fast with a clear error when a caller provides more than one of `version`, `channel`, or `globalJson`.

#### Scenario: Multiple selectors provided

- **WHEN** a caller provides both `version` and `channel` (or any two of the three selectors)
- **THEN** provisioning fails with an error identifying the conflicting selectors
- **AND** no install is attempted

### Requirement: Custom shared cache install directory

The system SHALL install SDKs into a custom shared cache directory rather than the install tool's default per-user directory. The cache directory SHALL be `~/.cache/jenkins-pipeline/dotnet` on macOS and Linux agents, and `%LOCALAPPDATA%\cache\jenkins-pipeline\dotnet` on Windows agents. Multiple SDK versions SHALL be able to coexist under this directory. The install directory and selector SHALL be passed to the install scripts as CLI arguments (e.g. `--install-dir`/`--version`, `-InstallDir`/`-Version`), not environment variables, so the exact invocation is visible in the build log.

#### Scenario: Install into cache directory on unix

- **WHEN** an SDK is provisioned on a unix agent
- **THEN** the official install script is invoked with an explicit `--install-dir` of `~/.cache/jenkins-pipeline/dotnet`
- **AND** the SDK is not installed into the default `$HOME/.dotnet` location

#### Scenario: Install into cache directory on Windows

- **WHEN** an SDK is provisioned on a Windows agent
- **THEN** the official install script is invoked with an explicit `-InstallDir` of `%LOCALAPPDATA%\cache\jenkins-pipeline\dotnet`
- **AND** the SDK is not installed into the default `%LocalAppData%\Microsoft\dotnet` location

#### Scenario: Multiple versions coexist

- **WHEN** two different SDK versions are provisioned into the cache directory over time
- **THEN** both remain available under the cache directory (each under its own `sdk/<version>` subfolder)

### Requirement: Concurrent installs are serialized

The system SHALL serialize concurrent SDK installs targeting the same cache directory on the same agent using a self-healing lock, so that simultaneous pipeline runs do not corrupt the shared cache. A lock held beyond a timeout SHALL be treated as stale and broken with a warning.

#### Scenario: Two installs race for the same cache directory

- **WHEN** two provisioning runs on the same agent attempt to install into the same cache directory at the same time
- **THEN** one run acquires the lock and installs while the other waits
- **AND** the waiting run proceeds once the lock is released

#### Scenario: Stale lock recovery

- **WHEN** a lock has been held longer than the configured timeout because a previous run crashed
- **THEN** the lock is broken with a warning and provisioning continues

### Requirement: Resolved version and source are logged

The system SHALL log, for every provisioning invocation, the effective SDK version, the selector that resolved it, the install directory, and whether the SDK was already cached (cache hit) or freshly installed. The exact version SHALL be resolved even when the selector is a channel or `global.json`. The logged description SHALL distinguish between the different possible sources of the resolved version — an explicit `version` or `channel` argument, a `global.json` (explicit or auto-detected), or the org-wide default — so it is always possible to tell from the log alone how the version was deduced.

#### Scenario: Fresh install is logged

- **WHEN** a requested SDK is not already present in the cache directory and is installed
- **THEN** the log records the resolved exact version, the selector used, the install directory, and that it was a fresh install

#### Scenario: Cache hit is logged

- **WHEN** a requested SDK is already present in the cache directory
- **THEN** the log records the resolved exact version, the selector used, the install directory, and that it was a cache hit

#### Scenario: Explicit argument vs. global.json vs. org-wide default are distinguishable

- **WHEN** the same concrete version is resolved once via an explicit `version` argument, once via a `global.json`, and once via the org-wide default fallback (three separate invocations)
- **THEN** each invocation's log line has a distinctly-worded description identifying its own source, so the three log lines are not identical to each other

### Requirement: Exact selectors avoid unnecessary network calls

When the selector resolves to a known exact version up front (an explicit `version` argument, or the org-wide default fallback), the system SHALL check the cache directory for that version before downloading the official install script, and SHALL skip the download entirely on a cache hit. Only a floating selector (an explicit `channel`, or a `global.json`-derived channel) — whose concrete version is not known in advance — SHALL require downloading the official install script to resolve it via a dry run before the cache can be checked.

#### Scenario: Exact version cache hit makes no network calls

- **WHEN** a caller provides `version: "8.0.401"` and that version is already present in the cache directory
- **THEN** the official install script is not downloaded
- **AND** no dry-run resolution is performed

#### Scenario: Floating channel selector still requires a dry-run

- **WHEN** a caller provides `channel: "8.0"` (or a `global.json`-derived channel)
- **THEN** the official install script is downloaded and invoked with a dry run to resolve the concrete version, even if that version turns out to already be cached
