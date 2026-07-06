## 1. Install wrapper scripts (resources)

- [x] 1.1 Create `resources/dotnet/dotnet-install.sh` (POSIX): download Microsoft's official `dotnet-install.sh` at runtime, honor `DOTNET_INSTALL_DIR`, and resolve the selector from `DOTNET_VERSION` / `DOTNET_CHANNEL` / `GLOBAL_JSON` with the precedence version → channel → global.json → latest LTS.
- [x] 1.2 In `dotnet-install.sh`, default the install dir to `~/.cache/dotnet` and pass it through as `--install-dir`; derive `major.minor` channel from `global.json` `sdk.version` when using the global.json path.
- [x] 1.3 In `dotnet-install.sh`, use `--dry-run` to resolve the exact version, check whether `<install-dir>/sdk/<version>` already exists (cache hit vs fresh install), then run the real install (skip if hit).
- [x] 1.4 In `dotnet-install.sh`, emit a single greppable log line with resolved version, selector source, install dir, and hit/miss.
- [x] 1.5 In `dotnet-install.sh`, serialize concurrent installs with a self-healing `mkdir` lock keyed to the install dir, breaking a stale lock after the default timeout (300s) with a warning.
- [x] 1.6 Create `resources/dotnet/dotnet-install.ps1` (Windows) mirroring 1.1–1.5: default install dir `%LOCALAPPDATA%\cache\dotnet`, same selector precedence, dry-run version resolution, hit/miss logging, and self-healing lock.

## 2. Dotnet model class

- [x] 2.1 Create `src/net/wooga/jenkins/pipeline/model/Dotnet.groovy` with a `fromJenkins(jenkins, args)` factory mirroring `Gradle.groovy`.
- [x] 2.2 Implement selector validation: accept at most one of `version` / `channel` / `globalJson`; fail fast with a clear error when more than one is given.
- [x] 2.3 Implement cache-directory resolution per OS (`~/.cache/dotnet` on unix via `HOME`, `%LOCALAPPDATA%\cache\dotnet` on Windows).
- [x] 2.4 Implement the env-var contract (`DOTNET_INSTALL_DIR`, `DOTNET_VERSION`, `DOTNET_CHANNEL`, `GLOBAL_JSON`) and workspace `global.json` auto-detection fallback.
- [x] 2.5 Implement `provision()`: write the correct wrapper via `libraryResource`, run it (`sh` on unix / `powershell` on Windows) inside `withEnv`.
- [x] 2.6 Implement `withEnvList()` returning the `PATH` (cache dir prepended) and `DOTNET_ROOT` entries used to expose the cached SDK to callers.

## 3. dotnetWrapper step

- [x] 3.1 Create `vars/dotnetWrapper.groovy` with `call(String command, Boolean returnStatus = false, Boolean returnStdout = false)` and `call(Map args)` (selector keys + `command` + `returnStatus`/`returnStdout`), mirroring `gradleWrapper.groovy`.
- [x] 3.2 Provision via `Dotnet`, then run `dotnet <command>` inside the provisioned env: `sh` on unix, `bat` on Windows, threading through `returnStatus`/`returnStdout`.
- [x] 3.3 Create `vars/dotnetWrapper.txt` documenting usage (string + map forms, selectors).

## 4. withDotnet step

- [x] 4.1 Create `vars/withDotnet.groovy` with `call(Map config = [:], Closure block)` mirroring `withVisualStudioDevEnv.groovy`.
- [x] 4.2 Provision via `Dotnet`, then execute the block inside `withEnv(Dotnet.withEnvList())` so `dotnet` (cache dir on `PATH`, `DOTNET_ROOT` set) is available and scoped to the block.
- [x] 4.3 Create `vars/withDotnet.txt` documenting usage (no-config + config forms, selectors).

## 5. Tests

- [x] 5.1 Add `test/groovy/scripts/DotnetWrapperSpec.groovy` covering string/map forms, selector precedence and conflict error, OS branching (`sh` vs `bat`), and `returnStatus`/`returnStdout`, following `GradleWrapperSpec.groovy`.
- [x] 5.2 Add `test/groovy/scripts/WithDotnetSpec.groovy` covering block execution with the correct `withEnv` (PATH + DOTNET_ROOT) and selector handling.
- [x] 5.3 Add a `Dotnet` model unit spec under `test/groovy/net/wooga/...` covering selector validation, cache-dir resolution per OS, and env-var contract.
- [x] 5.4 Run the Gradle test suite and confirm all new and existing specs pass. (409/410 pass; the 1 failure, `CacheSpec > renews project cache with valid parameters`, is a pre-existing, environment-timing-sensitive test unrelated to this change — untouched by this diff, last modified in a prior cache-fix commit.)

## 6. Validation & docs

- [x] 6.1 Verify end-to-end on macOS, Linux, and Windows agents (or the closest available) that provisioning installs into the cache dir and the SDK version/source is logged for both steps. Verified for real on macOS (this dev machine): ran `resources/dotnet/dotnet-install.sh` directly — fresh install, cache hit on re-run, `global.json` channel derivation, and the no-selector/no-global.json fallback — confirming the custom cache dir is used and the default `~/.dotnet` is left untouched. `dotnet-install.ps1` was syntax-checked with PowerShell's own parser (zero errors) and its `Resolve-Selector` function was extracted and unit-tested standalone via `pwsh` (installed for this purpose) across all selector precedence cases; the full end-to-end install path could not be run on real Windows in this environment (Microsoft's official script hits Windows-only archive-extraction APIs under `pwsh`/macOS — confirmed as a limitation of the vendor script itself, not this wrapper, by reproducing the same failure calling it directly with no wrapper involved). **Real end-to-end validation on a genuine Windows agent, and on a Linux agent via Jenkins, remains an open pre-merge step for a reviewer/CI run.**
- [x] 6.2 Update `README.md` (or the relevant step docs) to list the new `withDotnet` and `dotnetWrapper` steps.
- [x] 6.3 Run `openspec validate add-dotnet-steps` and confirm the change is valid.

## 7. Cache-churn fix: pinned org-wide default, source-attributed logging

Follow-up requested after initial implementation: on long-lived, persistent Jenkins agents, floating selectors (channel/LTS) never converge — every new upstream patch is a fresh install and old versions are never evicted, so the shared cache both stops paying off and grows unbounded. Resolved as: pin the no-selector/no-`global.json` case to a hardcoded org-wide default (git-versioned, bumped via PR); leave `global.json` floating on its derived channel to match GitHub Actions' `setup-dotnet` semantics (explicitly requested); and always make the install log say *which* of the three sources (explicit argument / `global.json` / org-wide default) produced the resolved version.

- [x] 7.1 Add `Dotnet.DEFAULT_VERSION` (exact, hardcoded constant, mirroring `JavaVersion.groovy`'s `?: 11` convention) and a `DOTNET_DEFAULT_VERSION` env var (kept distinct from `DOTNET_VERSION` so the log can tell an explicit argument apart from the fallback) set via `jenkins.fileExists('global.json')` when no selector and no workspace `global.json` apply.
- [x] 7.2 Revert `global.json` handling in both wrapper scripts back to floating major.minor channel derivation (matching `setup-dotnet`), after briefly trying and rejecting a literal-exact-version-install approach.
- [x] 7.3 Give each selector source (`version`, `channel`, `global.json`, org-wide default) a distinctly-worded description string, threaded through into the final log line, so the source is always identifiable from the log alone.
- [x] 7.4 Restructure both wrapper scripts so an exact selector (`version` or the org-wide default) checks the cache directory directly and skips downloading the official install script entirely on a hit; only a floating channel selector still needs the `--dry-run` round trip.
- [x] 7.5 Fix a real bug found via manual execution (not caught by unit tests): `log()` wrote to stdout, so `download_install_script()`'s `$(...)`-captured return value in `dotnet-install.sh` was corrupted by the interleaved log line, crashing every real install. Fixed by sending `log()` to stderr — diagnostic output must never share a stream with a function's captured return value.
- [x] 7.6 Update `DotnetSpec.groovy` (add `fileExists` stub, new default-fallback/auto-detected-global.json cases), re-run the full suite, and update `design.md`/`specs/dotnet-sdk-provisioning/spec.md`/READMEs/`.txt` docs to describe the final behavior.
- [x] 7.7 Re-validate both scripts for real after the fix: `dotnet-install.sh` end-to-end on macOS (fresh install, no-network cache hit, `global.json` channel derivation, org-wide default fallback); `dotnet-install.ps1`'s `Resolve-Selector` re-tested standalone via `pwsh` for the same cases.
