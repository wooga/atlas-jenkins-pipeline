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
- **Report from a `post { failure {...} }` block alone, with no capture at all.** This is the
  first thing to reach for, and it can't work on its own: a `post` block receives a build result,
  not the tool's output, so the only ways to get the findings text into the message are to scrape
  the console log (`currentBuild.rawBuild.getLog()` — needs script approval, is brittle, and is
  full of unrelated pipeline noise) or the rejected tool-posts-to-Slack option above. Capture is
  what gives any `post` block something to send; the two compose rather than compete, and the
  design below deliberately keeps the reporting side inside `post`.
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
  findings by matching a text pattern** (e.g. lines starting with `Error: `/`Warning: `). A tool's
  console output typically mixes human-readable progress ("Executing validator: X", printed for every validator
  regardless of outcome) with the actual findings, and a combined capture forces the caller to
  reconstruct "just the findings" by parsing text formatted for a human terminal. That coupling
  is fragile in the wrong direction — it breaks silently the moment the tool's message format
  changes for unrelated reasons (reordering fields, adding a new severity, localizing text), with
  no compiler or test on the Jenkins side to catch it.

All four are worse than teaching `runDotnetTool` to duplicate a tool's stdout and stderr into two
*separate* caller-named files — the same structural separation Unix CLI tools already use by
convention (stdout for primary/progress output, stderr for diagnostics and errors) — so a caller
that wants "just the findings" asks for the stderr stream specifically, with no text parsing and
no coupling to how the tool formats a message.

## What Changes

- `runDotnetTool`/`Dotnet.runTool` gain two new options, `stdoutFile` and `stderrFile` (unix/macOS
  only, both optional and independent, zero behavior change when omitted). Each names a
  workspace-relative file that stream is duplicated into inside the generated script, while still
  streaming live to the console. An unrequested stream is left completely untouched, so capturing
  only stderr costs nothing on stdout.
- **The step's own return/throw contract is unchanged.** Because the output travels out of band —
  in files the caller names, reads with `readFile`, and owns — the step does not have to give up
  throwing in order to hand back text. A nonzero tool exit still fails the build exactly as it does
  today unless the caller also passes `returnStatus: true`, so failure stays declarative and only
  the *reporting* is the caller's business. This is what lets the reporting live in
  `post { always { ... } }`: the file survives the call, so a post block can read it whether the
  stage passed or failed. It also means `returnStatus` composes with capture instead of being
  mutually exclusive with it.
- The capture files are truncated at the start of the generated script, before any early exit
  (the `DOTNET_CLI_HOME` guard, or a `mkfifo` failure) can happen, so a leftover file from an
  earlier build on a reused workspace is never read back as this run's output. They may still
  legitimately be empty, which callers must treat as "nothing to report" rather than an error.
- Paths are validated, never sanitized: quotes, `$`, backticks, backslashes, newlines, absolute
  paths, `..` segments, and naming the same file for both streams are all rejected with a clear
  error at call time. Sanitizing by substitution would quietly write somewhere other than the path
  the caller was promised and is about to read.
- Windows (`bat`) is out of scope for this change — passing either option on a Windows agent
  throws immediately with a clear message. Unlike `loginShell`/`umask`/`logCommandToStdErr`, which
  are silently ignored there, silently producing *no file* for a caller that is about to `readFile`
  it would surface far from the mistake.
- No requirement on how any individual .NET tool splits its own output between stdout and
  stderr — the library duplicates whichever streams were asked for; whether a given tool's
  stderr is actually useful on its own is up to that tool's own conventions. (Separately, as a
  consumer of this change, `adv5-config-val` would route its actual findings to stderr and keep
  progress logging on stdout — tracked in `tasks.md`, not part of this library change itself.)

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `dotnet-tool-steps`: adds one new requirement — a tool's stdout and stderr can each be
  duplicated into a caller-named workspace file, independently of and without altering the
  existing `returnStatus`/throw behavior, on unix/macOS agents only.

## Impact

- `src/net/wooga/jenkins/pipeline/model/Dotnet.groovy`:
  - `runTool()` gains the `stdoutFile`/`stderrFile` options and validates them (mirroring the
    existing `validateSelectors`/`validateNugetConfig` pattern already used in the constructor):
    shell-unsafe characters, absolute paths, `..`, one file named for both streams, and any use on
    a non-unix agent are all rejected.
  - A new private `captureOutputScriptSh(command, stdoutFile, stderrFile)` helper builds the
    duplicate-each-requested-stream script variant, alongside the existing `shScript()` used for
    the non-capturing path. It emits a FIFO and a `tee` only for the streams that were asked for.
  - `scriptPreambleLines()` gains a `preGuardLines` parameter, so the capture variant's file
    truncation lands after `umask` (for the caller's intended permissions) but before the
    `DOTNET_CLI_HOME` guard (so an early exit can't leave a stale file behind).
  - The generated script's `sh()` call passes the caller's `returnStatus` straight through, so
    `runTool` keeps its normal return/throw contract. Only the FIFOs are cleaned up, via a
    best-effort `rm -f` in a `finally`; the capture files deliberately outlive the call, since
    that is what a `post` block reads. The library never reads them itself.
- `vars/runDotnetTool.groovy`: the `call(Map args)` overload forwards `args.stdoutFile` and
  `args.stderrFile` to `dotnet.runTool(...)` alongside the existing `returnStatus`.
- `vars/runDotnetTool.txt`: documents the new options, the unchanged return/throw contract, and
  the `post { always { ... } }` reporting pattern they enable.
- `test/groovy/net/wooga/jenkins/pipeline/model/DotnetSpec.groovy` and
  `test/groovy/scripts/RunDotnetToolSpec.groovy`: new coverage (see `tasks.md`).
- No changes to `withDotnetTool`, `withDotnet`, `dotnetWrapper`, the SDK-install or tool-install
  locking added in `serialize-dotnet-tool-install`, or any existing consumer pipeline's calling
  convention — this is purely additive to `runTool`'s unix branch.
