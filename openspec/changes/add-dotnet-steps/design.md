## Context

The library already has two comparable patterns to build on:

- `gradleWrapper(command)` (`vars/gradleWrapper.groovy`): a command-style wrapper that delegates to a `Gradle` model class (`src/net/wooga/jenkins/pipeline/model/Gradle.groovy`) which encapsulates OS branching (`sh`/`bat`), environment handling, and command formatting. It exposes both a `call(String ...)` and a `call(Map)` signature.
- `withVisualStudioDevEnv { block }` (`vars/withVisualStudioDevEnv.groovy`): a block-style step that shells out to set up an environment, converts the output into `KEY=VALUE` pairs, and runs the given block inside `withEnv(pairs)`.

`wooga/adventure5-jenkins-pipeline#76` provides a working reference for .NET SDK provisioning: a single `installDotnet()` step that runs a vendored wrapper script (`resources/dotnet/dotnet-install.sh` / `.ps1`) which downloads Microsoft's official `dotnet-install` script at runtime, derives a channel from `global.json`, installs into the tool's own default per-user directory, and serializes concurrent installs with a self-healing lock directory. Note: this repo has **no `resources/` directory yet** — like the reference PR, this change introduces the first use of `libraryResource`-loaded script files.

Our requirements diverge from that reference in these ways, which drive most of the design below:
1. Two consumer-facing steps (`withDotnet` block-style, `dotnetWrapper` command-style) instead of one bare `installDotnet()`.
2. The SDK selector must support an explicit `version`, a `channel`, or a `globalJson` path — not just an implicit `global.json` read.
3. Installs must land in a custom shared cache directory (`~/.cache/dotnet` / Windows equivalent), not the tool's own default per-user directory.
4. Every invocation must log the effective SDK version and how it was resolved (cache hit vs. fresh install, and from which selector).

### Resolved decisions (from stakeholder)
- **Cache root**: XDG-standard `~/.cache/dotnet` on macOS/Linux; `%LOCALAPPDATA%\cache\dotnet` on Windows (mirrors the unix path shape and avoids confusion with dotnet's own default `%LocalAppData%\Microsoft\dotnet`).
- **Fallback**: when no selector is given and no `global.json` is found, install a **pinned org-wide default version** (see Decision 9) — not a floating channel/LTS.
- **`global.json` handling**: its `sdk.version`'s major.minor is installed as a **floating channel** (tracking the latest patch in that feature band), matching how GitHub Actions' `setup-dotnet` resolves a `global.json`. Teams bump their `global.json` to move to a newer SDK; we don't try to second-guess `rollForward` semantics.
- **API surface**: minimal — only `version` / `channel` / `globalJson` (plus `command` / block). Lower-level knobs (lock timeout, architecture, install-dir) stay internal with sane defaults.

> **Post-implementation revision**: after shipping the first version of this change (which floated on `LTS` for the no-selector case, like `global.json`), the stakeholder flagged that *any* floating selector on long-lived, persistent Jenkins agents causes cache churn — every new patch Microsoft ships is a fresh install, and old versions are never evicted, so the shared cache both stops paying off and grows unbounded. See Decision 9 for the fix adopted for the no-selector case. The `global.json` case was deliberately left floating (see above) to match `setup-dotnet` semantics — GitHub Actions users already expect a `global.json` to track the latest patch in its channel, and honoring `rollForward` "properly" (e.g. by pinning to the literal `sdk.version`) is unsafe on its own since that literal version frequently isn't a real shipped build (SDK versions carry a 3-digit feature band, e.g. `10.0.100`, so a `global.json` written with a bare `10.0.0` floor would 404 if installed verbatim).

## Goals / Non-Goals

**Goals:**
- Provide `dotnetWrapper(command)` and `withDotnet(config) { block }` shared steps with a consistent calling convention matching `gradleWrapper` / `withVisualStudioDevEnv`.
- Support selecting the SDK via exactly one of: explicit `version`, `channel`, or `globalJson` path; fall back to a workspace-root `global.json`, then to a pinned org-wide default version.
- Install SDKs into a custom shared cache directory, side by side by version, on macOS, Linux, and Windows agents.
- Reuse Microsoft's official install scripts (downloaded at runtime) rather than reimplementing SDK installation.
- Make the effective SDK version and how it was resolved (cache hit vs. fresh install, and from what selector) visible in the build log for every invocation of either step.
- Handle concurrent installs into the shared cache directory safely on a single agent.

**Non-Goals:**
- Cross-agent / network cache sharing — this is a local, per-agent disk cache only.
- Uninstalling or garbage-collecting old SDKs from the cache directory.
- Runtime-only / ASP.NET-runtime / .NET Framework installs — SDK installs only, matching the reference PR's scope.
- Vendoring/pinning a specific version of Microsoft's install scripts — like the reference PR, we fetch `dotnet-install.sh` / `.ps1` at runtime from `https://dot.net/v1/...`.
- Exposing advanced knobs (lock timeout override, architecture selection, install-dir override) in the public step API for this first version.

## Decisions

### 1. Shared provisioning logic lives in a `Dotnet` model class, consumed by both steps

Mirror `Gradle.groovy`: add `src/net/wooga/jenkins/pipeline/model/Dotnet.groovy` encapsulating selector validation, OS branching, invocation of the vendored install wrapper, computation of the cache directory, and construction of the `withEnv` variable list. Both `vars/withDotnet.groovy` and `vars/dotnetWrapper.groovy` construct a `Dotnet` instance and call into it, rather than duplicating logic across the two `vars/` scripts.

*Alternative considered*: duplicate the install/log logic directly in each `vars/*.groovy` file (as the reference PR does for its single step). Rejected — with two entry points sharing the same behavior, duplication risks the two steps drifting.

> **Post-implementation lesson (real-pipeline bug)**: `Dotnet`'s constructor originally called `validateSelectors(...)`, a method on the same class, directly. Since Jenkins CPS-transforms every method on classes under `src/` by default, and constructors themselves cannot be CPS-transformed (they can't pause/resume), this leaked an unhandled `hudson.remoting.ProxyException` / `CpsCallableInvocation` in real pipelines — invisible to the Spock/`jenkins-pipeline-unit` test harness, which doesn't perform real CPS transformation. Fixed by annotating `validateSelectors` with `@NonCPS` (matching the existing `BuildVersion.groovy` convention). **Lesson for future model classes in this library**: avoid calling same-class (or other CPS-transformed) methods from a constructor; if unavoidable, the called method must be `@NonCPS` and must not itself invoke Jenkins pipeline steps.

### 2. Step API / signatures

Both steps take the same optional selector keys: `version`, `channel`, `globalJson`.

```groovy
// dotnetWrapper — command style (mirrors gradleWrapper)
dotnetWrapper "build --configuration Release"          // string form; selector auto-detected
dotnetWrapper(command: "test", channel: "8.0")         // map form; explicit selector
dotnetWrapper(command: "test", returnStatus: true)     // returnStatus/returnStdout parity with gradleWrapper

// withDotnet — block style (mirrors withVisualStudioDevEnv)
withDotnet { sh "dotnet build" }                       // no-config form; selector auto-detected
withDotnet(version: "8.0.401") { sh "dotnet build" }   // explicit selector
```

- `dotnetWrapper` exposes `call(String command, Boolean returnStatus, Boolean returnStdout)` and `call(Map args)` (where `args.command` carries the command and `args.version/channel/globalJson` carry the selector), matching `gradleWrapper`.
- `withDotnet` exposes `call(Map config = [:], Closure block)`, matching `withVisualStudioDevEnv`.
- The **string** form of `dotnetWrapper` has no place for a selector, so it always uses auto-detect (global.json → org-wide default). Callers needing a selector use the map form.

*Alternative considered*: a separate public `installDotnet()` step (as in the reference PR) plus these two. Rejected for now — out of the requested scope; both steps provision internally, so a standalone install step is not needed by callers.

### 3. Selector precedence, validation, and fallback

Resolution order when building the install invocation:

1. `version` (exact SDK version, e.g. `8.0.401`) → install script `--version` / `-Version`.
2. `channel` (e.g. `8.0`, `LTS`, `STS`) → install script `--channel` / `-Channel`. Only used when a caller explicitly asks for it — this is a deliberate, opt-in float.
3. `globalJson` (explicit path) → read `sdk.version`, derive `major.minor`, install as a floating channel (same derivation as the reference PR; matches `setup-dotnet`).
4. No selector → auto-detect `global.json` in the workspace root (`${WORKSPACE}/global.json`); if present, treat as case 3.
5. Nothing found → install the **pinned org-wide `Dotnet.DEFAULT_VERSION`** (exact, not floating — see Decision 9).

If more than one of `version` / `channel` / `globalJson` is explicitly given, **fail fast** with a clear error rather than silently picking one — an ambiguous request is more likely a caller mistake than an intentional override chain.

*Alternative considered*: silent layered precedence allowing all three at once. Rejected — hides likely caller mistakes for little benefit.

### 4. Custom shared cache directory, passed explicitly to the install scripts

Unlike the reference PR (which deliberately relies on the tool's default install directory and avoids `--install-dir`), we pass an explicit install directory:
- macOS / Linux: `~/.cache/dotnet`
- Windows: `%LOCALAPPDATA%\cache\dotnet`

The wrapper scripts pass this to the official `dotnet-install` script via `--install-dir` / `-InstallDir`. Microsoft's install scripts support multiple SDK versions coexisting under one install root (each version gets its own `sdk/<version>/` subfolder), so no extra bookkeeping is needed for multiple cached versions.

Both steps set `DOTNET_ROOT` (= cache dir) and prepend the cache dir to `PATH` for the duration of the block / command, so the resolved `dotnet` is the cached one rather than any system copy. Because the cache dir is deterministic per-OS, `Dotnet.groovy` computes it directly (via `HOME` / `LOCALAPPDATA`) and does not need to capture it from script output — only the resolved *version* is dynamic, and that is logged by the script itself (see Decision 6).

*Alternative considered*: keep the tool's own default per-user directory (reference PR behavior). Rejected per explicit requirement.

> **Post-implementation revision** (code review): the cache root moved one level deeper to `~/.cache/jenkins-pipeline/dotnet` / `%LOCALAPPDATA%\cache\jenkins-pipeline\dotnet`, to be collision-safe with other tools/libraries that might otherwise also claim a bare `~/.cache/dotnet`. This silently orphans any `~/.cache/dotnet` populated by testing an earlier version of this branch — no migration code was added (cache GC is a Non-Goal), so that stale directory is simply abandoned; an agent operator can delete it manually if reclaiming the space matters.

### 5. Groovy ↔ wrapper-script contract (environment variables)

`Dotnet.groovy` communicates the selector and target to the vendored scripts purely through environment variables (set via `withEnv` before invoking the script), keeping the scripts free of positional-argument parsing:

| Env var | Meaning | Set when |
|---------|---------|----------|
| `DOTNET_INSTALL_DIR` | Explicit cache root to install into | always |
| `DOTNET_VERSION` | Exact SDK version | `version` selector given |
| `DOTNET_CHANNEL` | Channel to install (floating) | `channel` selector explicitly given |
| `GLOBAL_JSON` | Path to a `global.json` to derive a floating channel from | `globalJson` given or workspace `global.json` auto-detected |
| `DOTNET_DEFAULT_VERSION` | Exact org-wide default SDK version | none of the above apply (see Decision 9) |

The wrapper scripts apply the same precedence as Decision 3 over whichever of these are set, and error out if *none* of them is set — `Dotnet.groovy` is the single source of truth for selector resolution and always sets exactly one before invoking the script (the scripts are not meant to be run standalone). Note `DOTNET_DEFAULT_VERSION` is intentionally a distinct variable from `DOTNET_VERSION` rather than reusing it: this lets the install log say *why* a version was chosen — an explicit caller argument vs. the org-wide fallback — instead of the two being indistinguishable in the output. The lock timeout keeps its internal default (300s) and is not surfaced as an env var in the public API.

> **Superseded** (code review, see Decision 12): this env-var contract was replaced by CLI arguments (`--install-dir`/`--version`/`--channel`/`--global-json`/`--default-version`, and their `-InstallDir`/`-Version`/`-Channel`/`-GlobalJson`/`-DefaultVersion` PowerShell equivalents) so the selector actually being installed is visible directly in the Jenkins console log line that invokes the wrapper script, rather than only inside the script's own log output. The table above documents the original, no-longer-current contract for historical context. `DOTNET_INSTALL_LOCK_TIMEOUT` is the one exception — it stayed an internal env var, since it has no console-visibility need.

### 6. Cache-hit detection and version/source logging

Inside the vendored wrapper scripts (whose `sh`/`powershell` output is already captured in the Jenkins console), the check differs by whether the selector is **exact** or **floating**:

- **Exact** (`version` argument, or the `DOTNET_DEFAULT_VERSION` fallback): the target version is already known, so the cache is checked by directory presence (`<install-dir>/sdk/<version>`) *before* even downloading the official install script — a full cache hit costs zero network calls.
- **Floating** (`channel` argument, or `global.json`-derived channel): the concrete version isn't known up front, so the official install script is downloaded and invoked once with `--dry-run` to resolve it, then the same directory check applies.

In both cases: run the real install (idempotent; the official script no-ops if already present) unless it was a hit, and log a single, greppable line capturing the selector's source-specific description (e.g. `explicit version argument (8.0.401)`, `channel 8.0 from global.json at ./global.json (sdk.version 8.0.100)`, `org-wide default version (no global.json found)`), the resolved version, the install dir, and hit/miss — e.g.
`[dotnet-install] Using SDK 8.0.401 (channel 8.0 from global.json at ./global.json (sdk.version 8.0.100)) - cache hit at ~/.cache/dotnet`.

Each selector source gets a distinctly-worded description specifically so the log always answers "how was this version deduced — an explicit argument, `global.json`, or the org-wide default?" without needing to inspect env vars.

This gives both `withDotnet` and `dotnetWrapper` identical logging for free, without a separate Groovy-side `dotnet --list-sdks` round trip.

*Alternative considered*: compute hit/miss and version from Groovy by shelling out after the wrapper runs. Rejected — extra round-trips when the wrapper already has the information mid-flow.

### 9. Pinned org-wide default version for the no-selector, no-`global.json` case

When no selector is given and no `global.json` is found in the workspace, install a **hardcoded, exact SDK version** (`Dotnet.DEFAULT_VERSION`, a `static final String` constant on the `Dotnet` model class) rather than a floating channel/LTS.

This follows the repo's existing convention for tool-version defaults: `JavaVersion.groovy`'s `resolveVersion(...)` falls back to a hardcoded `?: 11` when no version file or caller-supplied default applies. Like that constant, `DEFAULT_VERSION` is bumped by a deliberate PR when the org wants to move the default forward — fully git-versioned and auditable, and identical across every agent in the fleet (unlike an auto-resolved-and-cached "first agent to touch it wins" value, which could vary by agent and by when it was first touched).

*Why not apply the same fix to `global.json`*: unlike the no-selector case (which has no existing external convention to match), teams with a `global.json` already have an established expectation — from `dotnet` CLI tooling and from GitHub Actions' `setup-dotnet` — that it tracks the latest patch in its declared channel. Overriding that with our own pinning behavior would surprise anyone porting a pipeline from GitHub Actions or running `dotnet` locally, for a churn problem that's real but secondary to that consistency (also, per Decision 3's `rollForward`-avoidance rationale, we can't safely pin to the literal `sdk.version` anyway).

*Alternative considered*: auto-resolve the "no selector" case once (via the same `--dry-run` flow as a channel) and persist the resolved version in a marker file inside the cache dir, reusing it indefinitely until the file is invalidated. Rejected in favor of the hardcoded constant — the marker-file approach is harder to audit (which version is "pinned" isn't visible in git, only by inspecting a live agent's disk), can drift between agents depending on when each first resolved it, and needs its own invalidation/refresh policy (TTL or an opt-in `refresh` flag) to ever move forward — complexity not justified for this case when a plain git-versioned constant does the job.

### 7. Command execution for `dotnetWrapper`

After provisioning, run the command against the cached SDK inside the same `withEnv` (PATH + DOTNET_ROOT): `sh "dotnet ${command}"` on unix, `bat "dotnet ${command}"` on Windows. No `powershell` needed here — invoking an already-provisioned binary doesn't need the richer scripting the install step uses. `returnStatus` / `returnStdout` are threaded through to the `sh`/`bat` call for parity with `gradleWrapper`.

### 8. Architecture handling

The official install scripts auto-detect the agent architecture (including Apple Silicon `arm64`), so no explicit `--architecture` handling is added. This keeps the minimal API surface; if an agent needs a non-native SDK (e.g. x64 under Rosetta), that is out of scope for this version.

### 10. Automatic wooga_nuget feed registration and credentials (post-implementation addition)

Requested after a real consumer hit exactly the boilerplate this whole feature is meant to remove: every .NET pipeline needing packages from the company's private Artifactory NuGet feed had to hand-write `dotnet nuget add source ...`, `withCredentials([usernamePassword(...)])`, and `export NuGetPackageSourceCredentials_wooga_nuget=...` themselves. Since this is explicitly a company-wide, always-applicable setting (not per-project), both `withDotnet` and `dotnetWrapper` now do this automatically as part of `Dotnet.withProvisionedEnv()`:

1. **Idempotent source registration**: `dotnet nuget add source <url> --name wooga_nuget`, guarded by `dotnet nuget list source --format Short | grep -qF <url>` (unix) / `Select-String -SimpleMatch` (Windows) first — `dotnet nuget add source` errors if a source with that URL is already registered (confirmed by real execution: it dedups by URL, not by `--name`), and this writes to the **user-level** `NuGet.Config`, so on a persistent agent it's a one-time cost per agent user account, consistent with how the SDK cache itself already assumes agent persistence.
2. **Credentials**: `jenkins.withCredentials([usernamePassword(credentialsId: 'artifactory_read', usernameVariable: 'JFROG_USER', passwordVariable: 'JFROG_PASS')])` wraps the block/command, exporting `NuGetPackageSourceCredentials_wooga_nuget=Username=<user>;Password=<pass>` — the standard NuGet CLI convention for per-source env-based credentials — for its duration.

`artifactory_read` is not a new credential invented for this feature — it's an existing, already-hardcoded convention in this same library (`javaLibs.groovy`, `buildWDK.groovy`, `buildUnityWdkV2/V3/V4.groovy`), confirming it's genuinely available wherever this shared library is used.

*Alternative considered*: make this opt-in via a parameter (e.g. `withDotnet(nugetFeed: true)`). Rejected per explicit requirement — this is meant to be zero-configuration, company-wide default behavior, matching the "minimal API surface" philosophy already established for this change (Decision 9's `DEFAULT_VERSION` is the same pattern: an org-wide constant, not a per-call knob).

*Risk accepted*: every `withDotnet`/`dotnetWrapper` call now hard-depends on the `artifactory_read` credential resolving in whatever Jenkins context it runs. If a pipeline uses these steps in a context where that credential isn't visible (e.g. a sandbox/personal Jenkins instance without the org's global credential store), the call fails even for builds that don't need the private feed at all. Accepted because the credential is already a load-bearing, widely-hardcoded assumption elsewhere in this exact library.

> **Post-implementation revision** (code review, see Decision 12): `wooga_nuget`/`artifactory_read` are no longer hardcoded on `Dotnet.groovy` itself. The model class is now fully generic with respect to NuGet (source/credentials are plain optional constructor parameters, defaulting to "do nothing"); the wooga-specific defaults moved to a new `DotnetNugetConfig` class, applied only by the `vars/*.groovy` step scripts. Behavior for existing callers is unchanged — `withDotnet`/`dotnetWrapper`/`withDotnetTool`/`runDotnetTool` all still apply these defaults automatically — but `withDotnet`/`dotnetWrapper` callers can now override the source/credentials or opt out entirely (`nuget: false`), which the original hardcoded-constant design had no way to express.

> **Further post-implementation revision** (real consumer follow-up, see tasks.md section 20): source registration moved from the user-level `NuGet.Config` (governed by `DOTNET_CLI_HOME`) to a workspace-local `./nuget.config` file, created via `dotnet new nugetconfig` if one doesn't already exist. Reason: a project-level `nuget.config` takes precedence over — and, via the `<clear />` element `dotnet new nugetconfig`'s own template includes by default, can fully override — a user-level config's registered sources (confirmed by real execution). Relying solely on the DOTNET_CLI_HOME-scoped user config meant the registration could be silently invisible to `dotnet` on any workspace where a project-level config existed for any reason. `ensureNuGetSource()`'s idempotent check-then-add shape is unchanged, just retargeted at the local file via `--configfile ./nuget.config`. Credentials are deliberately **not** part of this change: no `--username`/`--password`/`--store-password-in-clear-text` are added to the `dotnet nuget add source` call, even though `--store-password-in-clear-text` would be required to store a password this way on non-Windows platforms (confirmed by real execution: NuGet refuses to store an encrypted password there at all, since encryption depends on Windows DPAPI) — writing a secret into any file, including a workspace-local one, has a larger blast radius than the existing `NuGetPackageSourceCredentials_<source>` env-var mechanism (Decision 10, point 2), which already works regardless of which `NuGet.Config` file registered the source name, and needed no changes.

## Risks / Trade-offs

- **[Risk]** Runtime download of Microsoft's install script (no vendored/pinned copy) means a `dot.net` outage or breaking change hits all consumers at once. → **Mitigation**: same trade-off the reference PR accepted; Microsoft keeps this script strongly backwards-compatible, and vendoring adds ongoing maintenance.
- **[Risk]** Custom `--install-dir` is a less-exercised path through the official scripts than their default location. → **Mitigation**: validate end-to-end on macOS, Linux, and Windows agents (as the reference PR did via a downstream consumer build) before merging.
- **[Risk]** Shared cache dir widens the blast radius of a corrupted/partial install (affects every job on the agent needing that version). → **Mitigation**: rely on the official script's idempotency/verification; document that clearing `~/.cache/dotnet` (or the Windows equivalent) is the recovery step.
- **[Trade-off]** A `channel` argument or a `global.json`-driven build still re-runs the download-script + `--dry-run` round trip on every call, even on a cache hit, because the concrete version isn't known up front. → Acceptable for CI (a few seconds); `version` and the org-wide default fallback skip this entirely (directory check only, no network). A build calling the step many times in a tight loop could also hoist a single `withDotnet { ... }` around the work instead.
- **[Trade-off]** Builds with a `global.json` still churn the shared cache over time as Microsoft ships new patches in that channel (same as `setup-dotnet` on GitHub Actions) — old versions are never evicted. → Accepted as consistent with GitHub Actions parity (Decision 9); only the no-selector/no-`global.json` case gets a fully stable, pinned version. Cache eviction/GC remains a Non-Goal for this change.
- **[Trade-off]** Determining hit/miss and exact version requires filesystem/string handling inside the bash/PowerShell wrappers rather than structured data. → Acceptable; the reference scripts already parse `global.json` with `grep`/`ConvertFrom-Json`.
- **[Trade-off]** `dotnetWrapper` writes the install wrapper into the workspace (`.ci/`, per the reference pattern), lightly polluting the workspace. → Acceptable and consistent with the reference PR; the file is small and regenerated each run.
- **[Risk]** Both steps now hard-depend on the `artifactory_read` Jenkins credential resolving in the calling context (Decision 10) — a pipeline without access to it fails on every `withDotnet`/`dotnetWrapper` call, even ones that don't need the private NuGet feed. → **Mitigation**: `artifactory_read` is already a hardcoded, widely-used credential ID elsewhere in this exact library, so this isn't a new assumption for consumers of this repo.
- **[Risk, found post-implementation on a real Windows agent]** `dotnet-install.ps1` built its `-Channel`/`-Version` selector as an array (`@('-Channel', $value)`) and passed it via `@selectorArgs` splatting. In PowerShell, splatting an **array** binds its elements **positionally**, not as `-flag value` pairs — only splatting a **hashtable** maps keys to named parameters. This silently misbound the literal string `"-Channel"` onto the official script's first positional parameter (`$Channel`) and the actual channel value onto its second (`$Quality`), surfacing as `'10.0' is not a supported value for -Quality option`. Invisible to the Groovy/Spock suite (never executes the `.ps1` body) and to earlier manual `pwsh` validation (which called the official script with hand-typed named args, never through this splat variable). → **Fixed**: `$selectorArgs` is now a hashtable; verified against a stub script reproducing the exact error, then re-verified end-to-end via `pwsh` with the exact failing scenario (`DOTNET_CHANNEL=10.0`).
- **[Risk, found on the same real Windows agent, one fix later]** The channel-selector dry-run capture used `-DryRun 2>&1 | Out-String`, but the official script logs via `Write-Host`, which writes to PowerShell's **Information** stream — not stdout or stderr — so `2>&1` silently captured nothing even though the text was visibly printed to the console. The regex then correctly found no match in an effectively empty string. This is PowerShell-specific: the equivalent bash capture (`--dry-run 2>&1 | grep ...`) has no such gap, since POSIX shells only have stdout/stderr and the bash official script only ever writes to those. → **Fixed**: capture with `*>&1` (all streams) instead of `2>&1`; verified with a minimal `Write-Host` stub (0 chars captured via `2>&1`, correct text via `*>&1`) and with a second stub reproducing the exact real console text, confirming correct extraction of the resolved version.

### 11. withDotnetTool / runDotnetTool: local (manifest-based) tools only, with a redirected cache

Requested as a follow-up: install a NuGet package as a dotnet tool and invoke it, reusing `withDotnet`'s SDK/credentials provisioning. Two mutually exclusive .NET tool mechanisms exist and don't interoperate: `--tool-path <dir>` installs create real shims in `<dir>` (invocable as a bare command once that dir is on `PATH`, but *not* discoverable by `dotnet tool run`), while a plain/local install registers the tool in a manifest (`.config/dotnet-tools.json` or workspace-root `dotnet-tools.json`) and is *only* invocable via `dotnet tool run <name>` (or `dotnet <name>`) — never as a bare PATH command. After clarifying with the stakeholder, both `withDotnetTool` and `runDotnetTool` use the **local/manifest** mechanism exclusively; `withDotnetTool`'s block invokes the tool itself via `dotnet tool run <toolBinary>` rather than expecting it on `PATH`.

**Cache relocation** (real-execution verified on this dev machine, not just documentation-inferred): a common assumption is that local tools cache under `~/.dotnet/tools/.store/` — this is wrong; `.store` is exclusively used by **global** (`--global`) and `--tool-path` installs. A local/manifest tool's actual package content is fetched into the standard NuGet global-packages folder (`~/.nuget/packages` by default), which the well-known `NUGET_PACKAGES` env var already redirects — confirmed by installing a real tool (`dotnetsay`) with `NUGET_PACKAGES` set to a scratch directory and observing the package land there instead of `~/.nuget/packages`. A second, much smaller resolver cache lives under `~/.dotnet/toolResolverCache/`, relocatable via the `DOTNET_CLI_HOME` env var (also confirmed by real execution: setting it moved the resolver cache to `<value>/.dotnet/toolResolverCache/...` and the tool still ran correctly via `dotnet tool run`).

Both env vars are set for the duration of tool install/run to:
- `NUGET_PACKAGES = <cache-dir>/tools/packages`
- `DOTNET_CLI_HOME = <cache-dir>/tools`

keeping tool state fully self-contained under the same managed cache tree as the SDK itself, isolated from both the real user's `~/.nuget`/`~/.dotnet` and from other tools/SDKs on the same agent.

**Idempotency** (also real-execution verified): unlike the `wooga_nuget` source registration, `dotnet tool install` for a local/manifest tool is *already* idempotent on its own — re-running it for an already-installed version reports "up to date" and exits 0, no custom check-then-install logic needed. A version *change* requires `--allow-downgrade` regardless of direction (confirmed: attempting a lower version without the flag errors; with it, it updates cleanly) — this is exactly why the stakeholder's original spec called for passing it whenever a version is given, and this was verified to be necessary, not just defensive.

`--create-manifest-if-needed` is passed on every install so the steps work turnkey on a repo with no pre-existing tool manifest (confirmed via real execution on a fresh workspace: it auto-creates the manifest and the tool runs immediately after).

*Alternative considered*: use `--tool-path <cache-dir>/tools/bin` + add that dir to `PATH`, letting `withDotnetTool`'s block invoke the tool as a bare command (matching the original example syntax). Rejected per stakeholder direction — `dotnet tool run` (explicitly requested for `runDotnetTool`) cannot resolve a `--tool-path`-only install, so unifying on one mechanism was necessary, and local/manifest was the chosen one.

> **Post-implementation fix** (real consumer bug, see tasks.md section 17): `DOTNET_CLI_HOME` doesn't just relocate the tool resolver cache directory as originally understood — it independently governs where `dotnet nuget`/`dotnet tool` reads and writes the user-level `NuGet.Config`, completely separate from the real `$HOME` (confirmed by real execution: registering a source under one `DOTNET_CLI_HOME` value makes it invisible under a different value, or under no override at all). Since `ensureNuGetSource()` (Decision 10) runs *before* `toolEnv()` redirects `DOTNET_CLI_HOME` for the tool cache, the source it registers is written to a *different* `NuGet.Config` than the one `dotnet tool install`/`dotnet tool run` actually consult once inside `toolEnv()`'s scope — so `dotnet tool install` only ever saw the default `nuget.org` feed and failed to find any private package, 100% deterministically, for every `withDotnetTool`/`runDotnetTool` call with a NuGet source configured. This was invisible during initial development because manual testing exercised `withDotnet`/`dotnetWrapper` (which never redirects `DOTNET_CLI_HOME` at all), not the tool-cache path specifically. Fixed by re-running `ensureNuGetSource()` a second time inside `withTool()`'s `toolEnv()`-scoped `withEnv` block, immediately before `installTool()` — idempotent, so the extra call is cheap and safe.

> **Further post-implementation revision** (root-cause redesign, see tasks.md section 18, supersedes the fix above): the two-scope problem above only existed because `NUGET_PACKAGES`/`DOTNET_CLI_HOME` were redirected *only* for the tool path (`toolEnv()`, layered on top of `withInstalledDotnet()`'s own scope) — plain `withDotnet`/`dotnetWrapper` never redirected them at all, so `ensureNuGetSource()` and any caller `dotnet restore`/`build` in that path still wrote to the user's real `~/.nuget/NuGet/NuGet.Config`. Stated as an explicit design pillar: **nothing this library does with `dotnet` should ever touch the user's default `~/.nuget`/`~/.dotnet` locations, under any code path** — not just the tool path. Fixed at the root by folding `NUGET_PACKAGES`/`DOTNET_CLI_HOME` (still pointed at `toolCacheDir()`, despite the now-dated name) directly into `withEnvList()`, applied unconditionally by every `withInstalledDotnet()` call. Consequences:
> - There is now only ever **one** `DOTNET_CLI_HOME` scope per `Dotnet` instance, so the two-scope bug class is eliminated by construction rather than worked around — `withTool()`'s second `ensureNuGetSource()` call from the previous fix was removed as no longer necessary.
> - Every method that shells out to `dotnet` (`ensureNuGetSource()`, `installTool()`, `runTool()`) now prepends a small guard clause (`requireDotnetCliHomeSh`/`Ps`/`Bat`) that fails loudly if `DOTNET_CLI_HOME` is ever unset, rather than silently falling back to the user's default location — a defensive backstop against a future regression reintroducing an unscoped call, not something expected to trigger in normal operation.
> - `install()` deliberately does **not** get this treatment: the vendored install scripts extract the SDK archive directly and never invoke `dotnet`/`nuget`, so there's no default-location leakage risk there to guard against.

### 12. Code-review refactor: CLI-argument contract, generic NuGet config, method renames (post-implementation revision)

PR review (`@Joaquimmnetto`) on the initial implementation requested several extensibility/clarity changes, addressed together as one refactor:

1. **CLI-argument contract for the wrapper scripts** (supersedes Decision 5): `Dotnet.groovy` now passes the install directory and selector to `dotnet-install.sh`/`.ps1` as CLI flags (`--install-dir`/`--version`/`--channel`/`--global-json`/`--default-version`, `-InstallDir`/`-Version`/`-Channel`/`-GlobalJson`/`-DefaultVersion`) rather than environment variables, so the exact invocation is visible directly in the Jenkins console log line, not just inside the script's own log output. `install()` no longer wraps the invocation in `withEnv` at all — it builds one shell/PowerShell command string and runs it directly. New `@NonCPS` `shQuote`/`psQuote` helpers quote each value for safe interpolation (single-quote wrap; `'` escaped as `'"'"'` in bash, doubled as `''` in PowerShell) — needed since a `globalJson` path may contain spaces and is now interpolated into a command string instead of passed through an env var. Real-execution verified (both a closed-loop `shQuote`/`eval` round trip in bash and a `psQuote` round trip through real `pwsh` parsing) that a space- and quote-containing value survives correctly.
2. **Generic `Dotnet` w.r.t. NuGet** (extends Decision 10, see the revision note there): `nugetSourceName`/`nugetSourceUrl`/`nugetCredentialsId` became plain optional constructor parameters (all `null` by default — NuGet is fully skipped when none are given), validated at construction (`nugetSourceName`/`nugetSourceUrl` must be given together; `nugetCredentialsId` requires a source). The wooga-specific defaults moved to `src/net/wooga/jenkins/pipeline/config/DotnetNugetConfig.groovy`.
3. **Method renames** for clarity: `provision()` → `install()`; `withProvisionedEnv()` → `withInstalledDotnet()`; the private selector-formatting method became `installArgs()` (now returns a semantic `Map`, not `"KEY=VALUE"` strings).
4. **`dotnetWrapper` map-form parameter parity**: once (2) landed, `dotnetWrapper`'s map form gained the same `nugetSourceName`/`nugetSourceUrl`/`nugetCredentialsId`/`nuget: false` override/opt-out as `withDotnet` (the string form still always applies the wooga defaults — it has no place for these keys).

**Further revision** (extraction into a shared conventions class): `withDotnet.groovy` and `dotnetWrapper.groovy` initially each carried a private `withNugetDefaults`/`nugetDefaults` method duplicating the override/opt-out merge logic. Following this repo's existing `fromConfigMap`/`mergeWithConfigMap` convention (see `WDKConfig.fromConfigMap`, `PipelineConventions.mergeWithConfigMap`), `DotnetNugetConfig` became an instance-based config object: a `static final standard` singleton holding the wooga defaults, a `mergeWithConfigMap(Map)` instance method encapsulating the override/opt-out logic (returns a new, all-`null` config when `configMap.nuget == false`), and a `toDotnetArgs()` helper shaping the result into the `Map` keys `Dotnet.fromJenkins()` expects. Both `vars/*.groovy` step scripts now call `DotnetNugetConfig.standard.mergeWithConfigMap(config).toDotnetArgs()` instead of duplicating the merge logic; `withDotnetTool`/`runDotnetTool` (no override, per the minimal-scope decision below) call `DotnetNugetConfig.standard.toDotnetArgs()` directly.

`withDotnetTool`/`runDotnetTool` deliberately stayed **out of scope** for the override/opt-out API — confirmed with the stakeholder that these two keep silently applying the wooga defaults with no new caller-facing parameters, since the reviewer's comments were scoped to `Dotnet.groovy`/`dotnetWrapper.groovy` only and there was no expressed need to extend it further.

## Migration Plan

Purely additive — no existing step or pipeline changes. Rollout: merge to master, then bump this library's version pointer in the consuming repo to pick up the new steps, validated as the reference PR was (a downstream PR temporarily pointing `@Library` at this branch). No rollback concerns beyond reverting the merge, since nothing existing depends on these steps yet.

## Open Questions

- None blocking. (Cache path, no-selector fallback, and API surface resolved with stakeholder above.) Remaining detail — the exact greppable log-line format — is a low-risk implementation choice to finalize during coding.
