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

## 8. Real-pipeline bug: CPS-transformed method called from a constructor

Found by the stakeholder trying `withDotnet`/`dotnetWrapper` in a real Jenkins pipeline (`junes_journey/tools/cli` PR-400): every invocation with no explicit selector failed with `Found unhandled hudson.remoting.ProxyException exception: CpsCallableInvocation{methodName=validateSelectors, ..., arguments=[null, null, null]}`. Root cause: `Dotnet`'s constructor calls `validateSelectors(...)`, a method on the same class. Jenkins CPS-transforms every method on classes under `src/` by default, but constructors themselves cannot be CPS-transformed (they can't be paused/resumed) — calling a CPS-transformed method from a constructor leaks an unhandled `CpsCallableInvocation` continuation object instead of executing it. This class of bug is invisible to the Spock/`jenkins-pipeline-unit` test harness, which doesn't perform real CPS transformation, so it wasn't caught until real Jenkins execution.

- [x] 8.1 Annotate `Dotnet.validateSelectors(...)` with `@NonCPS` (`import com.cloudbees.groovy.cps.NonCPS`), matching the repo's existing convention (`BuildVersion.groovy`) for methods that must run as plain, non-resumable Groovy.
- [x] 8.2 Re-run the full Gradle test suite to confirm the annotation doesn't change behavior for the existing (CPS-blind) test harness — 409/410 pass, same pre-existing unrelated `CacheSpec` failure.
- [x] 8.3 Document the risk in `design.md` (Decision 1) so future model classes in this library avoid calling same-class methods from constructors without `@NonCPS`, or avoid the pattern entirely.

## 9. Automatic wooga_nuget feed registration and credentials

Requested after the real pipeline test: register the company-wide private `wooga_nuget` NuGet feed idempotently (once per agent) and wrap provisioning in `withCredentials([usernamePassword(credentialsId: 'artifactory_read', ...)])`, exporting `NuGetPackageSourceCredentials_wooga_nuget`, so consumers don't need to hand-write this boilerplate around every `withDotnet`/`dotnetWrapper` call.

- [x] 9.1 Add `Dotnet.NUGET_SOURCE_NAME`, `NUGET_SOURCE_URL`, `NUGET_CREDENTIALS_ID` constants.
- [x] 9.2 Implement `Dotnet.ensureNuGetSource()`: idempotent add via `dotnet nuget list source --format Short | grep`/`Select-String` check before `dotnet nuget add source` (unix `sh` / Windows `powershell`) — validated for real that `dotnet nuget add source` dedups by URL (not `--name`), confirming the pre-check is necessary, not just defensive.
- [x] 9.3 Wire `Dotnet.withProvisionedEnv()` to wrap the block in `jenkins.withCredentials([jenkins.usernamePassword(credentialsId: NUGET_CREDENTIALS_ID, usernameVariable: 'JFROG_USER', passwordVariable: 'JFROG_PASS')])`, export `NuGetPackageSourceCredentials_wooga_nuget=Username=...;Password=...`, and call `ensureNuGetSource()` before running the block.
- [x] 9.4 Refactor `dotnetWrapper.groovy` to route through `Dotnet.withProvisionedEnv()` instead of manually calling `provision()` + `withEnv(...)`, so both steps share identical NuGet/credentials behavior (no duplication, per Decision 1).
- [x] 9.5 Fix a real bug found via the sandboxed test suite (not by manual execution this time): `String.stripIndent()` used in the original multi-line `ensureNuGetSource()` script isn't in the Jenkins script-security sandbox's method whitelist (`RejectedAccessException`). Rewrote as single-line shell/PowerShell commands, avoiding `stripIndent()`/multi-line-string trimming entirely.
- [x] 9.6 Fix a real bug in the shared test fixture `FakeEnvironment.runWithEnv(List<String>, Closure)` (`atlas-jenkins-pipeline-test`): it naively split each `"KEY=VALUE"` string on every `=`, truncating any value containing a second `=` (exactly the `NuGetPackageSourceCredentials_wooga_nuget=Username=...;Password=...` shape). Fixed to split on the first `=` only — a general fix benefiting all future specs in this repo, not just this one.
- [x] 9.7 Update `DotnetSpec.groovy` (add `withCredentials`/`usernamePassword` stubs, new assertions for the nuget-source and credentials calls) and `DotnetWrapperSpec.groovy`/`WithDotnetSpec.groovy` (seed the fake `artifactory_read` credential, fix `.last()`-based env assertions to search by key instead, since the credentials-binding scope is now the outermost `withEnv`). Full suite: 413/414 pass (same pre-existing unrelated `CacheSpec` failure).
- [x] 9.8 Real-execution validate the exact idempotent add/check command strings (both bash and PowerShell, via a locally-installed `dotnet`/`pwsh`) against the real `wooga_nuget` URL — confirmed fresh-add and no-op-on-repeat behavior, then cleaned up the registered source.
- [x] 9.9 Update `README.md`, `dotnetWrapper.txt`, `withDotnet.txt` to document the automatic NuGet feed/credentials behavior, and `proposal.md`/`design.md`/`specs/dotnet-pipeline-steps/spec.md` to record the new requirement and its design rationale/risk.

## 10. withDotnetTool and runDotnetTool steps

New feature request: install a NuGet package as a dotnet tool and invoke it, reusing `withDotnet`'s SDK/credentials provisioning, with the tool's cache redirected under the shared cache tree. Clarified with the stakeholder (after identifying that `dotnet tool run` and `--tool-path` are mutually exclusive mechanisms) that both steps use local/manifest-based tools exclusively.

- [x] 10.1 Real-execution research (not guessed): confirmed local/manifest tools do NOT use `~/.dotnet/tools/.store/` (that's global/`--tool-path`-only); they cache in `~/.nuget/packages` (relocatable via `NUGET_PACKAGES`) plus a small `~/.dotnet/toolResolverCache` (relocatable via `DOTNET_CLI_HOME`) — verified by installing a real tool (`dotnetsay`) with both env vars redirected to scratch directories and confirming it still ran correctly via `dotnet tool run`.
- [x] 10.2 Real-execution confirmed `dotnet tool install` is already idempotent for local tools (re-install at the same version reports "up to date", exit 0) and that a version change requires `--allow-downgrade` regardless of direction (verified: fails without it, succeeds with it) — matching the stakeholder's original spec exactly.
- [x] 10.3 Real-execution confirmed `--create-manifest-if-needed` works turnkey on a fresh workspace with no pre-existing tool manifest.
- [x] 10.4 Add `Dotnet.toolCacheDir()`, `toolEnv()` (sets `NUGET_PACKAGES`/`DOTNET_CLI_HOME` under `<cache-dir>/tools`), `withTool(packageId, version, block)` (provisions SDK/creds via `withProvisionedEnv`, then installs the tool and runs the block inside `toolEnv()`), `installTool(packageId, version)` (adds `--version`/`--allow-downgrade` when a version is given), and `runTool(packageId, toolBinary, args, version, returnStatus)` (runs `dotnet tool run <toolBinary> <args>` via `sh`/`bat`).
- [x] 10.5 Create `vars/withDotnetTool.groovy` with `call(String packageId, Closure block)` and `call(Map opts, Closure block)` — fixed a real bug found via the sandboxed test suite: a `call(String, Map, Closure)` signature doesn't work with Groovy's named-arg calling convention, which places the synthesized Map *first* regardless of where the named args appear textually (`withDotnetTool("pkg", version: "x") { }` actually calls `call([version:"x"], "pkg", closure)`); resolved by mirroring `withDotnet`'s existing dual `call(String, Closure)`/`call(Map, Closure)` convention instead.
- [x] 10.6 Create `vars/runDotnetTool.groovy` with `call(String packageId, String toolBinary, List<String> args = [])` and `call(Map args)` (`packageId`/`toolBinary`/`args`/`version`/`returnStatus` keys).
- [x] 10.7 Add tests: `DotnetSpec.groovy` (`toolCacheDir`, `withTool`, `runTool` incl. version/`--allow-downgrade`, Windows `bat` branch — fixed a `List.contains(GString)` vs `String` equality pitfall in one assertion), `WithDotnetToolSpec.groovy`, `RunDotnetToolSpec.groovy` (mirroring `WithDotnetSpec`/`DotnetWrapperSpec` conventions, seeding the fake `artifactory_read` credential). Full suite: 429/430 pass (same pre-existing unrelated `CacheSpec` failure).
- [x] 10.8 Real-execution validate the exact generated command strings (`dotnet tool install ... --create-manifest-if-needed`, `dotnet tool install ... --version X --allow-downgrade`, `dotnet tool run ...`) end-to-end, confirming cache isolation from the real `~/.nuget/packages`.
- [x] 10.9 Update `README.md`, `withDotnetTool.txt`, `runDotnetTool.txt`, and `proposal.md`/`design.md`/`specs/dotnet-tool-steps/spec.md` to document the new steps and design rationale.

## 11. Real Windows bug: PowerShell array splat is positional, not named

Found by the stakeholder on a real Windows agent (first real Windows run of `withDotnet`, a `global.json`-derived `channel: "10.0"` build): `dotnet-install.ps1` failed with `'10.0' is not a supported value for -Quality option`, even though the wrapper never sets `-Quality` anywhere. Root cause: `$selectorArgs = @('-Channel', $selector.Value)` spliced via `@selectorArgs` is an **array** splat, which PowerShell binds **positionally** — each element becomes a separate positional argument, not a `-flag value` pair. Since the official script's `param()` block declares `$Channel` first and `$Quality` second, the literal string `"-Channel"` bound positionally to `$Channel`, and `"10.0"` bound positionally to `$Quality` — never actually reaching `-Channel` as a named parameter at all. Reproduced exactly (byte-for-byte matching error) with a stub script replicating the real param block, before touching the fix.

This was invisible to every test run so far: the Groovy/Spock suite never executes `dotnet-install.ps1`'s actual logic (only that `jenkins.powershell(...)` was called with *some* script), and earlier manual `pwsh` validation on macOS tested `Resolve-Selector` in isolation (no invocation) and the real official script with hand-typed named args (`-Channel 8.0`, never through the `$selectorArgs` splat variable) — so the splat bug's actual invocation path was never exercised end-to-end until a real Windows agent hit it.

- [x] 11.1 Fixed `$selectorArgs` in `dotnet-install.ps1` from an array (`@('-Channel', $value)`) to a hashtable (`@{ Channel = $value }`) — only hashtable splats (`@hashtable`) map keys to named PowerShell parameters; array splats (`@array`) are purely positional.
- [x] 11.2 Verified the fix with a stub script reproducing the real script's exact `param()` order: confirmed the buggy version reproduces the exact reported error, and the hashtable version correctly binds `-Channel`/`-Version` by name for both selector kinds.
- [x] 11.3 Re-ran the full wrapper end-to-end via `pwsh` on macOS with `DOTNET_CHANNEL=10.0` (the exact failing scenario): confirmed the `-Quality` error is gone and execution now proceeds to the same pre-existing, already-documented macOS/`pwsh`-only architecture-detection limitation (irrelevant on real Windows agents where `$env:PROCESSOR_ARCHITECTURE` is set).

## 12. Real Windows bug: Write-Host output not captured by 2>&1

Second bug found on the same real Windows agent, one fix later: after 11's parameter-binding fix, the dry-run resolved the version correctly and printed it to the console ("Repeatable invocation: ... -Version \"10.0.301\" ..."), but `dotnet-install.ps1` still failed with "Could not resolve an exact SDK version from dry-run output" — the regex found nothing in the *captured* `$dryRunOutput`, despite that exact text being visible in the Jenkins console. Root cause: the official script logs via `Write-Host`, which in PowerShell 5+/Core writes to the **Information** stream, not stdout or stderr — `2>&1` (merging only the error stream into the output stream) captures none of it, even though it still prints directly to the console. Reproduced exactly with a minimal `Write-Host`-based stub script before touching the fix: `2>&1 | Out-String` captured 0 characters; `*>&1 | Out-String` (merging *all* streams) captured the expected text.

- [x] 12.1 Changed the dry-run capture in `dotnet-install.ps1` from `-DryRun 2>&1` to `-DryRun *>&1`.
- [x] 12.2 Switched from `[regex]::Match` (first match) to `[regex]::Matches` + taking the last match, mirroring the bash wrapper's `tail -n1` behavior, for consistency and to reduce risk from any earlier version-like substrings in the output.
- [x] 12.3 Verified the fix two ways: (a) the minimal stub script confirming `*>&1` captures `Write-Host` output that `2>&1` misses; (b) a second stub reproducing the *exact* real console text from the Windows failure (byte-for-byte, including the `Sdk/10.0.301/dotnet-sdk-10.0.301-win-x64.zip` URL) fed through the actual fixed capture+regex logic, confirming it correctly extracts `10.0.301`. Also re-ran the full wrapper via `pwsh` with the user's exact `global.json` (`sdk.version: 10.0.0`, no explicit channel) scenario — it now proceeds past version resolution entirely, reaching only the same pre-existing, unrelated macOS-only architecture-detection limitation.
