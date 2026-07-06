## Why

Pipelines that build .NET/C# projects (e.g. Unity wdks, tooling repos) currently have no shared way to provision and invoke the .NET SDK — each consumer would have to vendor install scripts or reuse Gradle just to get a `dotnet` binary on the agent. We already provide this pattern for other toolchains (`gradleWrapper` for Gradle, `withVisualStudioDevEnv` for VS build tools); .NET needs the same treatment so pipelines can install and use a specific SDK version consistently across macOS, Linux, and Windows agents.

## What Changes

- Add `dotnetWrapper(command)`: ensures the requested .NET SDK is installed, then runs `dotnet <command>` with it (mirrors `gradleWrapper`'s `gradleWrapper "command"` calling convention).
- Add `withDotnet(config) { block }`: ensures the requested .NET SDK is installed, exposes it on `PATH`/`DOTNET_ROOT` for the duration of the block, then runs the block (mirrors `withVisualStudioDevEnv { block }`).
- Both steps accept an optional SDK selector: a specific `version`, a `channel`, or a path to a `global.json` file to read the version from. Exactly one selector may be given; if none is given, fall back to a `global.json` in the workspace root if present.
- SDK provisioning uses Microsoft's official `dotnet-install.sh` / `dotnet-install.ps1` scripts (same approach as the reference PR), fetched/vendored as library resources, not hand-rolled install logic.
- Installs are **not** placed in the default per-user dotnet directory. Instead they go into a shared, custom cache directory: `~/.cache/dotnet` on macOS/Linux, and `%LOCALAPPDATA%\cache\dotnet` on Windows. This keeps installed SDKs cacheable/reusable across jobs on the same agent independent of the default dotnet CLI location.
- Concurrent installs on the same agent must be serialized safely (self-healing lock, as in the reference PR) since multiple pipeline runs may share the same agent and cache directory.
- Both steps must log which .NET SDK version is being used and where it was resolved/installed from (e.g. "already cached at `<path>`" vs. "installed via channel `X`" vs. "installed via global.json at `<path>`"), so build logs make the effective SDK version traceable without needing to inspect the cache directory.

## Capabilities

### New Capabilities
- `dotnet-sdk-provisioning`: shared logic to resolve a requested .NET SDK version/channel/global.json, install it (via the official install scripts) into the custom shared cache directory if not already present, and report/log the resolved version and its source.
- `dotnet-pipeline-steps`: the two consumer-facing Jenkins shared library steps, `withDotnet` and `dotnetWrapper`, built on top of `dotnet-sdk-provisioning`.

### Modified Capabilities
- None — this is purely additive; no existing shared step's requirements change.

## Impact

- New files under `vars/` (`withDotnet.groovy`, `withDotnet.txt`, `dotnetWrapper.groovy`, `dotnetWrapper.txt`) and `resources/dotnet/` (vendored `dotnet-install.sh` / `dotnet-install.ps1`, adapted for the custom cache directory).
- No changes to existing pipelines or steps; purely additive shared library surface.
- Consumers (e.g. Unity wdk pipelines, .NET tooling repos) can opt in to these steps once available; no forced migration.
