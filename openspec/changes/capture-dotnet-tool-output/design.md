## Context

`Dotnet.runTool()` today runs a tool via `jenkins.sh(script: shScript(command, ...), returnStatus: returnStatus)`
on unix agents. Jenkins' own `sh` step can return *either* the exit status (`returnStatus: true`)
*or* the captured stdout as a string (`returnStdout: true`) — never both from one call; setting
both is rejected by the step itself. `Dotnet.runTool()`'s signature only ever forwards
`returnStatus`, so there is currently no path, supported or not, to get a tool's printed output
back into Groovy.

The motivating consumer is `adventure5-tools`' `configs/release_configs_to_sbs/Jenkinsfile`,
which runs `adv5-config-val validate` via `runDotnetTool` and wants to forward the validator's
actual error messages to Slack when it fails, without teaching every individual .NET tool how to
post to Slack itself.

An earlier version of this design captured stdout and stderr *combined* into one string. That
was rejected on reflection: `adv5-config-val validate` prints human-readable progress
("Executing validator: X", once per validator, unconditionally) to the same stream as its actual
findings, so a combined capture would force any caller wanting "just the findings" to filter the
captured text by matching the message format (e.g. lines starting with `Error: `/`Warning: `).
That's a text-format dependency between the Jenkinsfile and the tool's `Console.WriteLine` call
that nothing would catch if either side changed independently.

## Goals / Non-Goals

**Goals:**
- Let a caller get a tool's stdout and stderr back as two separate strings, alongside its exit
  code, from one `runTool`/`runDotnetTool` call, on unix/macOS agents — so a caller that wants
  "just the tool's diagnostics/findings" can use whichever stream the tool already reserves for
  that, with no text parsing.
- Preserve live console output: a human watching the build must still see the tool's output as it
  runs, exactly as with the non-capturing path today - `captureOutput` must not make a stage go
  silent for the duration of the run.
- Make the new option's failure modes loud, not silent — combining it with `returnStatus`, or
  using it on Windows, is a call-time error, not a silent no-op or a return-shape surprise.
- Keep the change purely additive: no existing caller's behavior changes when `captureOutput` is
  omitted.

**Non-Goals:**
- Requiring any individual .NET tool to actually split its output between stdout and stderr in
  any particular way. The library captures whatever each stream already contains and hands both
  back; a tool that prints everything to stdout gets an empty `stderr` string back, and the
  caller still has the full `stdout` text to fall back to. Whether `adv5-config-val` specifically
  routes findings to stderr is a change to that tool, tracked separately in `tasks.md`, not part
  of this library capability.
- Windows (`bat`) support. `bat`'s own `returnStdout` doesn't have the same mutual-exclusion
  constraint `sh` does, so this could be added later with a different implementation — but no
  current or requested consumer runs tool validation on Windows agents, and mirrors the existing
  Windows-out-of-scope precedent set by both prior `dotnet-tool-steps` changes
  (`add-dotnet-steps`, `serialize-dotnet-tool-install`).
- A separate, purpose-built live-tailing mechanism beyond what `tee` already provides for free.
  Live streaming is a Goal (above), not something dropped - but it's achieved by duplicating the
  tool's output to both the capture file and the script's original stdout/stderr (see Decisions),
  not by building any new console-streaming machinery of our own. An earlier draft of this
  section incorrectly assumed a plain `>` file redirect alone would leave live streaming intact;
  that's wrong (a plain redirect *diverts* output to the file instead of the console - confirmed
  by real execution) and is why `tee` is required at all, not optional polish.
- Interleaving/reordering stdout and stderr relative to each other. Capturing them into separate
  files means any information about *when*, relative to each other, a given stdout line and a
  given stderr line were printed is lost — each stream is returned as its own ordered text, not
  merged. No current consumer needs cross-stream ordering; a tool that cares about this should
  encode order within a single stream itself (e.g. structured output on one stream only).

## Decisions

**Capture stdout and stderr into two separate files, not one combined stream.** Reverses the
combined-capture approach from the earlier draft of this design (see Context) specifically to
avoid coupling a caller's parsing logic to a tool's message-formatting choices. Two `tee`s in the
generated script cost nothing extra over one; the caller gets both back and picks whichever it
needs, or both.

**`tee` reading from named FIFOs, run as real background jobs (`command &` + `$!`), not `tee` via
process substitution (`> >(tee file)`).** This is the second iteration of this mechanism, and the
change was again forced by real execution, not reasoning: process-substitution subshells are never
added to the shell's job table, so a bare `wait` (or `wait $!`) never actually waits for them.
Confirmed directly: with a `tee` deliberately slowed down (`sleep 2` before it runs), `wait`
returned immediately on both bash 3.2 and 5.3, and the capture file did not exist yet at that point
- the original 5-repeated-runs verification only "passed" because `tee`'s own work happened to
finish before the next line ran, by luck, not because `wait` enforced anything. Named FIFOs plus a
genuine background job give a real PID that `wait <pid>` does block on - confirmed by the same kind
of test, this time blocking for the full injected delay and producing a complete file, on both bash
versions.

A plain `>`/`2>` file redirect (rejected even earlier, in the first draft of this mechanism) is
wrong for a different, independent reason: it *diverts* the command's stdout/stderr to the file
instead of the parent shell's own stdout/stderr - exactly what Jenkins' `sh` step watches to build
the live console log - so the whole run would go silent in Jenkins until it finished. `tee`
duplicates each stream to the file *and* passes it through to the script's own (console-connected)
stdout/stderr, so a human watching the build sees the tool's output as it runs, same as the
non-capturing path.

**`exec 3>&1 4>&2` saves the original stdout/stderr before anything reassigns fd1/fd2, and each
`tee` explicitly targets the corresponding saved fd (`>&3`/`>&4`).** Confirmed necessary by real
execution, not obvious from reasoning alone: without saving the fds first, the *second* `tee`'s own
passthrough copy silently inherits whatever fd1 had *already* been reassigned to by the *first*
redirection on the same line, cross-contaminating the stdout capture file with stderr content (or
vice versa) instead of writing to the real console. Explicitly targeting the saved fds removes the
ambiguity regardless of redirection order. This part of the mechanism was correct from the first
iteration and carried over unchanged into the FIFO-based version.

**The command's own redirects target the FIFOs directly, not a pipe and not the `tee` processes
themselves, so this doesn't reintroduce the "pipe corrupts $?" problem the args-smuggling
alternative (see proposal.md) would have hit.** `$?` immediately after the command still reflects
its own exit status, not `tee`'s or the shell's - confirmed by real execution across repeated runs
with a nonzero-exit fixture, on both the process-substitution and FIFO versions of this mechanism.
This is captured into a variable (`_exit_code=$?`) on its own line before anything else (closing the
saved fds, `wait`) can touch `$?`, and the script finally does `exit $_exit_code`, since `wait`'s
own exit status would otherwise become the script's.

**Capture via a script that returns status through `sh(returnStatus: true)`, not `sh`'s own
`returnStdout`.** Since `sh(returnStdout: true, returnStatus: true)` isn't a legal combination, and
we need both the text and a non-throwing exit code in one call, the script itself does the
capturing (via the `tee`/FIFO/fd mechanism above) and the `sh()` call underneath only ever asks for
the exit status; each file is then read back (guarded by `fileExists()` - see below) with
`jenkins.readFile()`, pinning `encoding: 'UTF-8'` explicitly rather than trusting the agent's
platform-default encoding, since .NET tools emit UTF-8 and validation messages plausibly contain
non-ASCII content (config paths, localized strings) that would otherwise mangle on the way to
wherever a caller forwards it. This keeps `sh`'s own step contract untouched and puts the "give me
status *and* text" behavior entirely inside `Dotnet.groovy`, where it can be tested directly against
the generated script string, rather than depending on an assumption about how Jenkins' `sh` step
might evolve.

**A script exit *before* the command ever runs never creates the capture files - guarded with
`jenkins.fileExists()` before each `readFile()`, returning an empty string rather than letting the
call throw.** `requireDotnetCliHomeSh()`'s own guard clause (checking `DOTNET_CLI_HOME`) runs before
any of the capturing setup, and exits the script early on failure; a missing/broken bash shebang
would fail identically. Without this guard, `readFile()` on a nonexistent file throws
`NoSuchFileException` - breaking the "captureOutput never throws on a bad exit" contract this
option is meant to provide, with a confusing file-not-found far from the actual cause instead of a
clear signal - exactly the kind of footgun already avoided for the Windows-rejection case.

**Cleanup (`rm -f` on both capture files) is wrapped in its own try/catch, swallowing a cleanup
failure rather than letting it propagate.** A `finally` block that itself throws replaces whatever
exception was already propagating from the `try` - so if the *capturing* `sh()` call fails for an
unrelated reason (e.g. an agent disconnect), a subsequent cleanup failure must not be allowed to
mask that real cause behind a lost-two-temp-files problem, which is a far smaller concern than
losing the actual reason a build failed.

**Bash, not POSIX `sh`, for the capture script - unconditionally, not gated behind `loginShell` like
`shScript()`'s shebang, though the mechanism itself no longer strictly requires it.** Named FIFOs,
background jobs, and `$!`/`wait <pid>` are all POSIX, not bash-specific - unlike the
process-substitution version, this mechanism *could* run under plain `sh` (e.g. dash). The
unconditional `#!/bin/bash` is kept anyway, purely for consistency with the rest of this class's
unix-path conventions (which already assume bash is available, e.g. for `loginShell`), not because
this specific mechanism demands it. Shared with `shScript()` via `scriptPreambleLines(...,
forceBash)` rather than a second, duplicated preamble (see below).

**Verification.** Unit tests assert on the generated script string, not on real bash execution
semantics (Groovy mocks stand in for `jenkins.sh`/`readFile`/`fileExists`) - they cannot by
themselves catch a subtle real-shell bug like the two above (the `wait`/process-substitution one, or
the fd-inheritance one). Both were confirmed, and the FIFO-based fix confirmed correct, by literally
running each generated script shape with a real bash (5.3, matching Linux Jenkins agents far more
closely than macOS's frozen bash 3.2) against a fixture tool that prints to both streams with
deliberate delays between lines and exits non-zero - across repeated runs, with timestamps
confirming output appeared progressively rather than being buffered, and (for the `wait` bug
specifically) a deliberately slowed-down `tee` proving the difference between "returns immediately"
(process substitution) and "genuinely blocks for the full delay" (FIFO + background job). See
`tasks.md` for a note that a real-Jenkins run (as opposed to local bash) is still worth doing once
this is deployed, mirroring how `serialize-dotnet-tool-install` had its own separate real-Jenkins
verification section.

**Deterministic file names, keyed by `toolBinary` and (when available) the current stage name, not
random ones.** `UUID.randomUUID()` and `Math.random()` aren't safely callable inside the Jenkins CPS
sandbox without extra script approval (the same category of restriction noted for other steps in
this library). Names derived from `toolBinary` plus `env.STAGE_NAME` when set (e.g.
`.dotnet-tool-stdout-${toolBinary}-${stageName}.log`, workspace-relative) are simple, require no
sandbox approval, and narrow the collision window from "the whole workspace" to "the same tool run
from the same stage in the same workspace" - cheap insurance against the specific case of a
Jenkinsfile validating two config sets in a `parallel {}` block sharing one workspace, which is not
a known current pattern but is a plausible future one.

**Accepted risk: two concurrent `captureOutput` calls for the *same* `toolBinary` in the *same*
stage in the *same* shared workspace can still collide on these files.** Not fully addressed by this
change, only narrowed (see above). This mirrors the `serialize-dotnet-tool-install` change's own
workspace-relative NuGet-config lock, which accepted an analogous scoping assumption rather than
building a more general per-invocation-unique scheme for a race with no known instance in current
pipelines. The failure mode if this is ever hit is silent cross-contamination of text that gets
forwarded wherever a caller sends captured output (e.g. Slack) - not a crash. If this is ever
observed in practice, the fix would be the same `mkdir`-lock pattern already used twice in this
file, not a bigger redesign.

**`loginShell`/`umask`/`logCommandToStdErr`/`captureOutput` are grouped into a single `options` Map
parameter on `runTool()`, rather than more trailing positional parameters.** `runTool()` already had
three trailing booleans/strings before this change; a fourth pushed real call sites past readable
(`runTool("MyTool", "mytool", [], null, false, false, null, false, true)`) and the trend would only
worsen with any future unix-only knob. Grouping them now, while `captureOutput` is still new and
this is the only PR touching the signature, is cheaper than doing it later once more callers exist.
`vars/runDotnetTool.groovy`'s public Map-based API is unaffected - this only changes the internal
`Dotnet.runTool()` signature and its direct callers (the var wrapper, and this project's own tests).

**`captureOutput` and `returnStatus` are mutually exclusive, validated at call time.** Rather than
silently letting one win (e.g. `captureOutput` implying `returnStatus` is ignored) or picking an
arbitrary precedence, `runTool()` throws `IllegalArgumentException` if both are explicitly `true`
— mirroring the existing `validateSelectors`/`validateNugetConfig` guard-clause pattern in this
same class for other option pairs that don't compose. `captureOutput: true` on its own already
returns the exit code (inside the result Map), so there is nothing `returnStatus: true` would add
— asking for it alongside is very likely a caller mistake (expecting the *old* return shape) that
should fail loudly rather than return a Map where an int was expected.

**`captureOutput: true` on Windows throws immediately, rather than silently having no effect.**
`loginShell`/`umask`/`logCommandToStdErr` already have documented "no effect on Windows" behavior,
because ignoring them changes nothing about the *return value*'s shape — a caller who set
`loginShell: true` on a Windows agent still gets back the exit status/throw behavior they expect.
`captureOutput` is different: it changes the return type from a bare status to a `Map`. Silently
ignoring it on Windows would mean the caller's code (which likely does `result.stderr`) breaks
with a confusing `MissingPropertyException` far from the actual cause, instead of a clear error at
the point of the mistake.

**Both output files are removed in all cases, including when the tool itself fails.** Each file is
only ever a transient conduit for one `runTool` call's result, not an artifact anyone downstream
should rely on existing — leaving them behind on failure (the case a caller most wants the output
*for*) would be exactly backwards, so removal is unconditional (success, tool failure, or an
exception while constructing/running the script), via a `finally`-equivalent in the generated
script or the surrounding Groovy, not conditioned on the tool's own exit code.

## Risks / Trade-offs

- **[Same-tool-same-workspace collision]** Covered above under Decisions; accepted, no known
  current instance, same class of risk already accepted by `serialize-dotnet-tool-install`.
- **[Caller must remember to fail the build itself]** Like `returnStatus: true` today,
  `captureOutput: true` never throws on the tool's own nonzero exit — a caller who wants the build
  to actually fail must call `error(...)` (or equivalent) themselves based on the returned
  `exitCode`. This is an existing, already-accepted caveat of `returnStatus`, now documented for
  `captureOutput` too, not a new category of risk.
- **[Separate-stream capture depends on a tool's own stdout/stderr split being meaningful]** If a
  tool dumps everything to stdout (as `adv5-config-val` does today, before the follow-up change
  tracked in `tasks.md`), `captureOutput`'s `stderr` field is simply empty and offers no benefit
  over the combined approach for that tool specifically — the caller falls back to `stdout` and
  is back to the original noise problem until the tool itself is updated. This is an accepted,
  visible trade-off (an empty string is an obvious signal to fall back, not a silent wrong
  answer), not a flaw in the capture mechanism itself.
- **[Lost cross-stream ordering]** Per the Non-Goals above; accepted, no current consumer needs
  it.
- **[Requires a real bash, not just any POSIX `sh`]** `>(...)` process substitution doesn't exist
  in plain POSIX `sh` (e.g. dash). Every unix/macOS Jenkins agent this library already assumes
  has a `bash` available for `loginShell`'s existing shebang, so this isn't a new environmental
  requirement - but it means `captureOutput` would fail outright (a shell syntax error, not a
  silent fallback) on a hypothetical unix agent with no `bash` on `PATH` at all. No such agent is
  known to exist in this fleet.

## Migration Plan

Purely additive to an existing shared-library step's public API (`runDotnetTool`/`runTool` gain
one new optional parameter, default `false`); no consumer-facing change for any existing caller
that doesn't pass `captureOutput`, no rollback concerns beyond reverting this change, no data
migration.

The `Dotnet.runTool()` internal signature change (collapsing `loginShell`/`umask`/
`logCommandToStdErr`/`captureOutput` into a single `options` Map, made during review - see
`tasks.md` §3a.9) is a different kind of change: it touches an existing method's *shape*, not just
adds a parameter, so it was worth confirming its actual blast radius rather than assuming
"internal" meant "safe." Verified org-wide, not assumed: a GitHub code search across every `wooga`
repo for both `net.wooga.jenkins.pipeline.model.Dotnet` (the class) and `runTool(` (the method) found
no caller anywhere outside this library's own `vars/runDotnetTool.groovy` and its test file - both
already updated to the new signature. `Dotnet` is genuinely reachable only through the `vars/`
steps; nothing external imports or calls it directly.

Separately, `runDotnetTool` (the *public* step, whose Map-based call signature and return shape
did not change at all) has exactly one other real external caller found via the same search:
`wooga/adventure5-jenkins-pipeline`'s `vars/adventure5Tools.groovy` (a shared step used across
content-build pipelines, not just this configs one), which calls the Map form with
`loginShell`/`logCommandToStdErr`/`umask`/`returnStatus` and does not use `captureOutput` -
unaffected either way.

## Open Questions

- Should a future increment add Windows (`bat`) support, given `bat`'s `returnStdout` doesn't
  have the same mutual-exclusion constraint as `sh`? Deferred — no current consumer needs it; can
  be scoped as its own change if that ever changes.
