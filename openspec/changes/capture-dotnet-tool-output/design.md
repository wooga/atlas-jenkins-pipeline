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

**The command's own invocation explicitly closes fds 3/4 (`3>&- 4>&-`), on top of the already-saved
fds being closed at the script level later.** Confirmed by real execution that without this, the
command (and any child it spawns) inherits fd 3/4 by default - ordinary fd inheritance, not
anything specific to this mechanism. This directly addresses the one thing this document flags as
plausible-but-unverified (see Risks): whether a lingering child holding the step's console
descriptors open could keep the Durable Task Plugin's step open, regardless of how it backs
stdout/stderr. Removing the command's own access to fds 3/4 removes that entire class of exposure
outright, independently of how that plugin question eventually resolves. This doesn't replace the
watchdog below - the watchdog backstops a lingering child still holding the *FIFO's write end*
open (fd 1/2, redirected), which closing fd 3/4 doesn't touch.

**`mkfifo ... || exit 125` fails fast on a genuine setup failure, rather than letting it cascade.**
Confirmed by real execution (a directory obstructing one of the FIFO paths) that without this, a
failed `mkfifo` doesn't stop the script - the forced shebang already escapes Jenkins' default
`sh -e`, so nothing else would stop it either - and it proceeds into a `tee`/redirect cascade that
lands in the same acknowledged indistinguishable-from-tool-failure shape as other setup-time
failures. `exit 125` is a recognizable "setup failed" sentinel, distinct from any exit code the
tool itself could plausibly produce, so at least this specific failure is now loud rather than
silent. Confirmed by real execution that the script now stops immediately with that exit code
instead of cascading.

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

**Both `toolBinary` and `STAGE_NAME` are sanitized before being used in a filename, via the same
`sanitizeForFilename()` helper.** `toolBinary` was interpolated raw in an earlier version of this
change - an inconsistency, not a different risk: it ends up in exactly the same shell-embedded
double-quoted paths as `STAGE_NAME`, so the same failure modes apply (a `/` breaks `mkfifo`; a `$`
or backtick would expand). The risk is lower in practice - `toolBinary` is a developer-written
literal in a Jenkinsfile, not build-time data like a stage name - but the sanitizer already existed,
so routing `toolBinary` through it too removes the inconsistency for one extra call. This is
deliberately scoped to the *filename* derivation only: `toolBinary` still (correctly) appears raw
in the `dotnet tool run <toolBinary>` invocation itself, since that's the actual binary name to
invoke, not a filename - sanitizing it there would break legitimate binary names and isn't a new
exposure introduced by this change; it predates `captureOutput` entirely and applies equally to the
non-capturing path.

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

**All four files (both capture files and both FIFOs) are removed in all cases, including when the
tool itself fails, is aborted, or the script dies early.** Each is only ever a transient conduit
for one `runTool` call's result, not an artifact anyone downstream should rely on existing —
leaving them behind on failure (the case a caller most wants the output *for*) would be exactly
backwards. The generated script's own trailing `rm -f` on the FIFOs only covers a clean run through
the whole script - an abort, kill, or (before the shebang-is-required fix below) an early death
under Jenkins' default `sh -e` skips it, confirmed by real execution to leak the FIFO paths into
the workspace. The Groovy-side `finally` therefore removes all four paths itself, unconditionally -
this is the only place cleanup is guaranteed to run regardless of how the script terminated, not
the script's own (best-effort, not guaranteed) trailing `rm -f`.

**A stale artifact at either FIFO path (from a previous crashed/killed run) is removed immediately
before `mkfifo`, not left for `mkfifo` to fail on.** Confirmed by real execution that this matters
specifically when the stale artifact is a *regular* file rather than no file or a stale FIFO:
`mkfifo` erroring on an existing FIFO would at least be a loud failure, but a stale regular file at
that path breaks capture silently instead - `tee` reading from a regular file hits EOF immediately
and exits, then `<command>`'s own redirect writes straight into that now-unpiped file, giving no
capture, no live console passthrough, and an exit code that still looks unremarkable.

**Capture filenames are keyed on `env.STAGE_NAME` (sanitized), not the raw value.** Interpolating
`STAGE_NAME` directly, as an earlier version of this change did, is a silent-wrong-answer bug, not
just untidy: confirmed by real execution that a stage name containing `/` (a plausible name, e.g.
"Validate/Configs") makes `mkfifo` fail outright - the tool never runs, and the caller gets back
`[exitCode: 1, stdout: "", stderr: ""]`, indistinguishable from a tool that genuinely failed while
printing nothing. A `$` or backtick is worse: since the stage name is interpolated into the
generated shell script rather than only used as an opaque path segment, either would expand inside
the double-quoted paths - an injection surface for whatever the stage name happens to contain, not
merely a broken filename. Sanitizing to a conservative safe set (`[A-Za-z0-9._-]`, everything else
replaced with `_`) removes both failure modes.

**A background watchdog bounds how long `wait` can block on a hung/lingering child of the tool.**
The FIFO/background-job fix above (see the `tee`/FIFO decision) makes `wait` a real barrier for the
first time - which is exactly the point, but it also means a child of `<command>` that outlives it
while still holding the inherited stdout/stderr (so the corresponding `tee` never sees EOF) would
now block `wait` forever, confirmed by real execution. This wasn't possible with the original
process-substitution version, precisely because its `wait` never actually waited for anything - so
this is a genuinely new risk introduced by fixing the sync bug, not a pre-existing one carried over.
A background job (`( sleep <timeout>; kill <tee pids> ) &`) is started right before `wait`, killed
once `wait` returns on its own, and otherwise fires after
`CAPTURE_OUTPUT_WATCHDOG_TIMEOUT_SECONDS` (300s) to force both `tee` processes to exit - degrading
to truncated captured output rather than hanging the step, and eventually the whole build, until
some surrounding `timeout()` (if any) fires. Confirmed by real execution to unblock correctly on a
genuinely hung child. In the normal case, killing only the watchdog *subshell* (`kill
"$_watchdog_pid"`) leaves the `sleep` it's blocked inside orphaned to run out its full timeout -
confirmed by real execution (a leaked, still-running `sleep` process after the script exited, on
both bash versions), not merely a theoretical gap. `pkill -P "$_watchdog_pid"` (kills the
subshell's child first) before `kill "$_watchdog_pid"` (then the subshell itself) leaves nothing
behind - confirmed by real execution. `pkill -P` isn't POSIX but is present on both Linux and macOS
agents.

**The forced shebang escapes Jenkins' default `sh -xe`, and this is load-bearing, not stylistic -
an earlier version of this document got this wrong.** The FIFO mechanism itself (`mkfifo`,
background jobs, `$!`/`wait <pid>`) is POSIX, not bash-specific, and the previous revision of this
section concluded from that alone that the shebang was purely a consistency choice. That's wrong:
confirmed by real execution that this exact script shape, run *without* any shebang under `sh -e`
(what Jenkins uses when no custom shebang overrides it), dies the instant `<command>` exits
non-zero - before `_exit_code=$?`, `wait`, or any cleanup ever runs, leaking both FIFOs and
reverting the capture to winning-by-luck, i.e. exactly the race the FIFO switch exists to fix in the
first place. *Some* shebang - not specifically bash - would suffice to escape `-e`; bash
specifically is kept for consistency with `loginShell`'s own bash requirement elsewhere in this
class, layered on top of the escape-`-e` requirement, not instead of it.

## Risks / Trade-offs

- **[Same-tool-same-stage-same-workspace collision]** Narrowed (not eliminated) by keying on
  `STAGE_NAME` too - see Decisions. Accepted, no known current instance, same class of risk already
  accepted by `serialize-dotnet-tool-install`.
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
- **[A hung/lingering child truncates output after the watchdog timeout, rather than blocking
  forever]** A genuine trade-off, not a free fix: if `<command>`'s output was still being written
  when the watchdog fires, whatever hadn't been flushed yet is lost from the capture (though the
  live console already saw it as it happened). Accepted because the alternative - blocking `wait`
  indefinitely - is strictly worse for a CI executor, and no current consumer's tools are known to
  leave lingering children; this is a defensive bound against a failure mode the .NET ecosystem
  does have precedent for (e.g. MSBuild node reuse / compiler-server processes outliving a parent),
  not a response to an observed incident.
- **[The lingering-child scenario itself is untested on real Jenkins]** Everything about the
  watchdog above (it fires, it unblocks `wait`, cleanup afterward leaves nothing running) was
  confirmed with local bash, not a real Jenkins agent - and this is the one part of this whole
  change where that distinction plausibly matters, not just formally. Closing fds 3/4 on the
  command's own invocation (see Decisions) removes one specific path by which a lingering child
  could hold the step's console descriptors open, regardless of how the Durable Task Plugin backs
  them - but it doesn't address a child still holding open the *FIFO's write end* (fd 1/2), which
  is what the watchdog exists for; whether that specifically keeps a real Jenkins step open the way
  it might with a pipe-backed stdout/stderr (as opposed to the file-backed implementation it's
  believed to use) is still a plausible-not-verified claim, unlike everything else in this
  document. Worth specifically checking in the real-Jenkins run already planned in `tasks.md`
  (§6.2), not just re-confirming the already-verified parts.
- **[A watchdog killing an already-exited tee's PID could theoretically hit a reused PID under the
  same agent user, in the narrow case where only one of the two tees lingers]** Accepted, not worth
  building anything for: the window is the watchdog's own timeout (300s), the failure is a
  suppressed error (`2>/dev/null` on the `kill`) rather than anything observable going wrong, and
  no current or plausible near-term consumer's usage pattern makes this likely enough to justify
  machinery narrower than "accept it."
- **[`Dotnet.runTool()`'s signature change from positional trailing parameters to a single
  `options` Map is source-breaking for any caller using the old positional form directly]** This is
  *not* the same claim as "purely additive" made elsewhere in this document about the `captureOutput`
  option itself - that claim is still true for the public `runDotnetTool` step, whose Map-based call
  signature and return shape didn't change at all. `Dotnet.runTool()` is a different matter: its
  parameter list actually changed shape, which breaks source compatibility for any caller still
  using the pre-existing positional form. Verified via an org-wide `gh search code` (see Migration
  Plan) that no such caller exists today, in this repo or any other `wooga` repo - but `Dotnet` is a
  public class other repos could import and call directly (`Dotnet.fromJenkins(this, ...)`), so
  "no current caller" is a fact established by searching, not a property of the change being
  "internal" by design. Re-verify with the same search before merging if meaningful time passes
  between this check and the actual merge.

## Migration Plan

The public `runDotnetTool` step is purely additive: its Map-based call signature and return shape
are unchanged for any existing caller that doesn't pass `captureOutput`, no rollback concerns
beyond reverting this change, no data migration.

`Dotnet.runTool()` itself is not purely additive - see the matching Risks bullet above for why, and
for what was actually verified (an org-wide `gh search code`) about whether a direct caller of the
old positional form exists today. In short: no caller was found anywhere outside this library's own
`vars/runDotnetTool.groovy` and its test file, both already updated.

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
