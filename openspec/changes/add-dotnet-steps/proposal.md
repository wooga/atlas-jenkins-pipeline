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
- Both steps also idempotently register the company-wide private `wooga_nuget` NuGet feed (once per agent) and bind the `artifactory_read` Jenkins credential for the duration of the block/command, exporting `NuGetPackageSourceCredentials_wooga_nuget` — removing boilerplate every .NET consumer would otherwise have to repeat to restore packages from that feed.
- Add `withDotnetTool(packageId) { block }` and `runDotnetTool(packageId, toolBinary, args)`: install a NuGet package as a local (manifest-based) dotnet tool — reusing `withDotnet`'s SDK provisioning and NuGet feed/credentials — then either run the given block (with the tool invocable via `dotnet tool run <toolBinary>`) or directly invoke the tool and return/fail based on `returnStatus`. Tool package caches are redirected under the same shared cache tree (`<cache-dir>/tools`), not the default `~/.nuget/packages` / `~/.dotnet` locations. An optional `version` pins the tool version (passing `--allow-downgrade` so it also works when a different version is already installed in the workspace's tool manifest).

## Capabilities

### New Capabilities
- `dotnet-sdk-provisioning`: shared logic to resolve a requested .NET SDK version/channel/global.json, install it (via the official install scripts) into the custom shared cache directory if not already present, and report/log the resolved version and its source.
- `dotnet-pipeline-steps`: the two consumer-facing Jenkins shared library steps, `withDotnet` and `dotnetWrapper`, built on top of `dotnet-sdk-provisioning`.
- `dotnet-tool-steps`: the two tool-focused Jenkins shared library steps, `withDotnetTool` and `runDotnetTool`, built on top of `dotnet-pipeline-steps`' SDK/credentials provisioning.

### Modified Capabilities
- None — this is purely additive; no existing shared step's requirements change.

## Impact

- New files under `vars/` (`withDotnet.groovy`, `withDotnet.txt`, `dotnetWrapper.groovy`, `dotnetWrapper.txt`, `withDotnetTool.groovy`, `withDotnetTool.txt`, `runDotnetTool.groovy`, `runDotnetTool.txt`) and `resources/dotnet/` (vendored `dotnet-install.sh` / `dotnet-install.ps1`, adapted for the custom cache directory).
- No changes to existing pipelines or steps; purely additive shared library surface.
- Consumers (e.g. Unity wdk pipelines, .NET tooling repos) can opt in to these steps once available; no forced migration.
- New implicit dependency: any pipeline using `withDotnet`/`dotnetWrapper` now requires the `artifactory_read` Jenkins credential to be resolvable in its context (already a widely-used, pre-existing credential ID in this library — see `javaLibs.groovy`, `buildWDK.groovy`).
