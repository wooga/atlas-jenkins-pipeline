## Why

`runDotnetTool`/`Dotnet.runTool` only ever exposes a tool's exit status (`returnStatus`) to the
calling pipeline — whatever the tool prints to stdout/stderr goes straight to the Jenkins
console log and is otherwise discarded. This blocks a real use case: a June's Journey config
validator (`adv5-config-val validate`, run via `runDotnetTool` from a `Wooga.Adv5.Tools`
Jenkinsfile) needs its actual validation errors forwarded to Slack when it fails, not just a
generic "build failed" alert.

Three approaches were considered and rejected before writing this proposal:

- **Duplicate the Slack-posting logic inside the .NET tool itself.** Works, but means every
  tool that wants failure detail in Slack re-implements an HTTP-webhook client, and the
  Jenkins-side `post { failure {...} } }` block (which already exists in most pipelines for the
  generic "build failed" case) ends up firing *again* for the same failure, producing two
  Slack messages in an awkward order for one real failure.
- **Smuggle shell redirection tokens into the `args` list** (e.g. `args: [..., "2>", "out.log"]`).
  `Dotnet.runTool` currently joins `args` with plain spaces and does not quote them, so this
  happens to work today — but it's an accident of the current implementation, not a documented
  contract. If a future change starts quoting each arg individually (a reasonable thing to do,
  e.g. to support argument values containing spaces), this silently breaks with no compile-time
  or build-time signal. It also still requires a second `runDotnetTool`/`slack:notify` call to
  read the file back and forward it, with the forwarded text now passing through several layers
  of shell/CLI argument parsing that a validation message's content (JSON snippets, quotes,
  `$`) can break in ways that are hard to catch outside of unlucky real input.
- **Capture stdout and stderr combined into one string, then let the caller pick out the real
  findings by matching a text pattern** (e.g. lines starting with `Error: `/`Warning: `). This
  was the first version of this proposal. Rejected on reflection: a tool's console output
  typically mixes human-readable progress ("Executing validator: X", printed for every validator
  regardless of outcome) with the actual findings, and a combined capture forces the caller to
  reconstruct "just the findings" by parsing text formatted for a human terminal. That coupling
  is fragile in the wrong direction — it breaks silently the moment the tool's message format
  changes for unrelated reasons (reordering fields, adding a new severity, localizing text), with
  no compiler or test on the Jenkins side to catch it.

All three are worse than teaching `runDotnetTool` to capture a tool's stdout and stderr as two
*separate* strings, alongside its exit code — the same structural separation Unix CLI tools
already use by convention (stdout for primary/progress output, stderr for diagnostics and
errors) — so a caller that wants "just the findings" asks for the stderr stream specifically,
with no text parsing and no coupling to how the tool formats a message.

## What Changes

- `runDotnetTool`/`Dotnet.runTool` gain a new `captureOutput` option (unix/macOS only, default
  `false`, zero behavior change when omitted). When `true`, the tool's stdout and stderr are each
  redirected to their own temp file inside the generated script, read back after the command
  completes, and the call returns `[exitCode: <int>, stdout: <string>, stderr: <string>]` instead
  of the bare status/throw behavior `returnStatus` gives today.
- `captureOutput: true` implies the same "don't throw on a nonzero tool exit" behavior
  `returnStatus: true` gives today — the caller always gets the exit code back and decides
  whether/how to fail the build. Combining `captureOutput: true` with `returnStatus: true` is
  rejected with a clear error at call time (mirrors Jenkins' own `sh` step, which forbids setting
  both `returnStdout` and `returnStatus` in one call), rather than silently picking one.
- Windows (`bat`) is out of scope for this change — calling with `captureOutput: true` on a
  Windows agent throws immediately with a clear message, rather than silently returning the
  wrong shape (a Map is a materially different contract than the plain exit code Windows callers
  get today, so silent no-effect — the existing behavior for `loginShell`/`umask`/
  `logCommandToStdErr` on Windows — would be a worse footgun here).
- No requirement on how any individual .NET tool splits its own output between stdout and
  stderr — the library captures both streams distinctly and returns both; whether a given tool's
  stderr is actually useful on its own is up to that tool's own conventions. (Separately, as a
  consumer of this change, `adv5-config-val` would route its actual findings to stderr and keep
  progress logging on stdout — tracked in `tasks.md`, not part of this library change itself.)

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `dotnet-tool-steps`: adds one new requirement — a tool's stdout and stderr can each be
  captured and returned alongside its exit code, as an alternative to the existing `returnStatus`
  option, on unix/macOS agents only.

## Impact

- `src/net/wooga/jenkins/pipeline/model/Dotnet.groovy`:
  - `runTool()` gains the `captureOutput` parameter and validates it isn't combined with
    `returnStatus` (mirroring the existing `validateSelectors`/`validateNugetConfig` pattern
    already used in the constructor for other mutually-exclusive option pairs), and throws if
    `captureOutput: true` is requested on a non-unix agent.
  - A new private `captureOutputScriptSh(command, stdoutFile, stderrFile)` helper builds the
    redirect-each-stream-to-its-own-file script variant, alongside the existing `shScript()` used
    for the non-capturing path.
  - New private `toolStdoutFile(toolBinary)`/`toolStderrFile(toolBinary)` helpers return
    workspace-relative, deterministic file paths (not random/UUID names — those aren't usable
    inside the Jenkins CPS sandbox without extra script-approval), keyed by `toolBinary` so two
    different tools running in the same workspace don't collide.
  - Both captured files are read via `jenkins.readFile()` and removed via a best-effort `rm -f`
    in all cases (success, tool failure, or an exception constructing/running the script), so a
    captured-output run never leaves stray files behind in the workspace.
- `vars/runDotnetTool.groovy`: the `call(Map args)` overload forwards `args.captureOutput` to
  `dotnet.runTool(...)` alongside the existing `returnStatus`.
- `vars/runDotnetTool.txt`: documents the new option and its `[exitCode, stdout, stderr]` return
  shape.
- `test/groovy/net/wooga/jenkins/pipeline/model/DotnetSpec.groovy` and
  `test/groovy/scripts/RunDotnetToolSpec.groovy`: new coverage (see `tasks.md`).
- No changes to `withDotnetTool`, `withDotnet`, `dotnetWrapper`, the SDK-install or tool-install
  locking added in `serialize-dotnet-tool-install`, or any existing consumer pipeline's calling
  convention — this is purely additive to `runTool`'s unix branch.
