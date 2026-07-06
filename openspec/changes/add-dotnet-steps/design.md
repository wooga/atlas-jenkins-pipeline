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
- The **string** form of `dotnetWrapper` has no place for a selector, so it always uses auto-detect (global.json → latest LTS). Callers needing a selector use the map form.

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

## Risks / Trade-offs

- **[Risk]** Runtime download of Microsoft's install script (no vendored/pinned copy) means a `dot.net` outage or breaking change hits all consumers at once. → **Mitigation**: same trade-off the reference PR accepted; Microsoft keeps this script strongly backwards-compatible, and vendoring adds ongoing maintenance.
- **[Risk]** Custom `--install-dir` is a less-exercised path through the official scripts than their default location. → **Mitigation**: validate end-to-end on macOS, Linux, and Windows agents (as the reference PR did via a downstream consumer build) before merging.
- **[Risk]** Shared cache dir widens the blast radius of a corrupted/partial install (affects every job on the agent needing that version). → **Mitigation**: rely on the official script's idempotency/verification; document that clearing `~/.cache/dotnet` (or the Windows equivalent) is the recovery step.
- **[Trade-off]** A `channel` argument or a `global.json`-driven build still re-runs the download-script + `--dry-run` round trip on every call, even on a cache hit, because the concrete version isn't known up front. → Acceptable for CI (a few seconds); `version` and the org-wide default fallback skip this entirely (directory check only, no network). A build calling the step many times in a tight loop could also hoist a single `withDotnet { ... }` around the work instead.
- **[Trade-off]** Builds with a `global.json` still churn the shared cache over time as Microsoft ships new patches in that channel (same as `setup-dotnet` on GitHub Actions) — old versions are never evicted. → Accepted as consistent with GitHub Actions parity (Decision 9); only the no-selector/no-`global.json` case gets a fully stable, pinned version. Cache eviction/GC remains a Non-Goal for this change.
- **[Trade-off]** Determining hit/miss and exact version requires filesystem/string handling inside the bash/PowerShell wrappers rather than structured data. → Acceptable; the reference scripts already parse `global.json` with `grep`/`ConvertFrom-Json`.
- **[Trade-off]** `dotnetWrapper` writes the install wrapper into the workspace (`.ci/`, per the reference pattern), lightly polluting the workspace. → Acceptable and consistent with the reference PR; the file is small and regenerated each run.

## Migration Plan

Purely additive — no existing step or pipeline changes. Rollout: merge to master, then bump this library's version pointer in the consuming repo to pick up the new steps, validated as the reference PR was (a downstream PR temporarily pointing `@Library` at this branch). No rollback concerns beyond reverting the merge, since nothing existing depends on these steps yet.

## Open Questions

- None blocking. (Cache path, no-selector fallback, and API surface resolved with stakeholder above.) Remaining detail — the exact greppable log-line format — is a low-risk implementation choice to finalize during coding.
