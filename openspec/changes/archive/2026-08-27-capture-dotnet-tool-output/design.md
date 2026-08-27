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

## Goals / Non-Goals

**Goals:**
- Let a caller get a tool's stdout and stderr back as two separate texts, on unix/macOS agents —
  so a caller that wants "just the tool's diagnostics/findings" can use whichever stream the tool
  already reserves for that, with no text parsing.
- Leave the step's own return/throw contract alone, so failing the build stays declarative and only
  the *reporting* becomes the caller's business. This is why the output goes to caller-named files
  rather than a return value (see Decisions): a Jenkins step can't both throw and return, so a
  return-value design would have to trade away throw-on-failure to hand back text.
- Make the captured output readable from a `post` block, so reporting can be attached to a stage
  declaratively and can cover the case where the tool exited zero but still printed something worth
  reporting.
- Preserve live console output: a human watching the build must still see the tool's output as it
  runs, exactly as with the non-capturing path today - capturing must not make a stage go silent
  for the duration of the run.
- Make the new options' failure modes loud, not silent — an unsafe or unreadable path, or use on
  Windows, is a call-time error, not a silent no-op.
- Keep the change purely additive: no existing caller's behavior changes when neither option is
  passed.

**Non-Goals:**
- Requiring any individual .NET tool to actually split its output between stdout and stderr in
  any particular way. The library duplicates whatever each requested stream already contains; a
  tool that prints everything to stdout leaves an empty `stderrFile`, and the caller can ask for
  `stdoutFile` too and fall back to that. Whether `adv5-config-val` specifically routes findings to
  stderr is a change to that tool, tracked separately in `tasks.md`, not part of this library
  capability.
- Cleaning up the capture files. They are deliberately left in the workspace: the caller named them,
  reads them itself, and a `post` block reading one has to be able to do so *after* `runTool`
  returned or threw. The FIFOs the mechanism uses internally are still cleaned up, since those are
  the library's own implementation detail, not something a caller ever names.
- Windows (`bat`) support. The `tee`/FIFO mechanism below is unix-only, and `bat` would need its own
  way to duplicate a stream to a file while still streaming live — addable later, but no current or
  requested consumer runs tool validation on Windows agents, and skipping it mirrors the existing
  Windows-out-of-scope precedent set by both prior `dotnet-tool-steps` changes
  (`add-dotnet-steps`, `serialize-dotnet-tool-install`).
- A separate, purpose-built live-tailing mechanism beyond what `tee` already provides for free.
  Live streaming is a Goal (above), not something dropped - but it's achieved by duplicating the
  tool's output to both the capture file and the script's original stdout/stderr (see Decisions),
  not by building any new console-streaming machinery of our own. Note that a plain `>` file
  redirect would *not* leave live streaming intact - it diverts output to the file instead of the
  console, confirmed by real execution - which is why `tee` is required at all, not optional polish.
- Interleaving/reordering stdout and stderr relative to each other. Capturing them into separate
  files means any information about *when*, relative to each other, a given stdout line and a
  given stderr line were printed is lost — each file holds its own stream in order, unmerged. No current consumer needs cross-stream ordering; a tool that cares about this should
  encode order within a single stream itself (e.g. structured output on one stream only).

## Decisions

**Capture stdout and stderr into two separate files, not one combined stream.** A combined capture
would couple a caller's parsing logic to a tool's message-formatting choices: `adv5-config-val
validate` prints human-readable progress ("Executing validator: X", once per validator,
unconditionally) to the same stream as its actual findings, so a caller wanting "just the findings"
would have to filter the captured text by matching the message format (e.g. lines starting with
`Error: `/`Warning: `). That's a text-format dependency between the Jenkinsfile and the tool's
`Console.WriteLine` call that nothing would catch if either side changed independently. Two `tee`s
in the generated script cost nothing extra over one; the caller asks for whichever stream it needs,
or both.

**`tee` reading from named FIFOs, run as real background jobs (`command &` + `$!`), not `tee` via
process substitution (`> >(tee file)`).** Forced by real execution, not reasoning:
process-substitution subshells are never added to the shell's job table, so a bare `wait` (or
`wait $!`) never actually waits for them. Confirmed directly: with a `tee` deliberately slowed down
(`sleep 2` before it runs), `wait` returned immediately on both bash 3.2 and 5.3, and the capture
file did not exist yet at that point - so a process-substitution version passes its verification
only when `tee`'s own work happens to finish before the next line runs, by luck, not because `wait`
enforced anything. Named FIFOs plus a genuine background job give a real PID that `wait <pid>` does
block on - confirmed by the same kind of test, this time blocking for the full injected delay and
producing a complete file, on both bash versions.

A plain `>`/`2>` file redirect is wrong for a different, independent reason: it *diverts* the
command's stdout/stderr to the file
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
ambiguity regardless of redirection order.

**The command's own redirects target the FIFOs directly, not a pipe and not the `tee` processes
themselves, so this doesn't reintroduce the "pipe corrupts $?" problem the args-smuggling
alternative (see proposal.md) would have hit.** `$?` immediately after the command still reflects
its own exit status, not `tee`'s or the shell's - confirmed by real execution across repeated runs
with a nonzero-exit fixture. This is captured into a variable (`_exit_code=$?`) on its own line before anything else (closing the
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
tool itself could plausibly produce, so at least this specific failure is loud rather than silent.
Confirmed by real execution that the script stops immediately with that exit code instead of
cascading.

**The output travels through files the caller names and owns, rather than being returned as
strings.** A Jenkins step cannot both throw and return a value, so a return-value design would have
to give up throwing on a nonzero tool exit in order to hand back text - which would mean every caller
writing `if (result.exitCode != 0) error(...)` to fail the build at all, and reporting that could
only ever happen inline, since a return value doesn't exist any more once the step has thrown.
Routing the output out of band avoids all of it:

- The step keeps its normal contract - `returnStatus` is passed straight through, and a nonzero tool
  exit throws exactly as it does without capture. Failing the build stays declarative; only the
  reporting is the caller's business. `returnStatus` therefore remains the single knob deciding
  between throwing and returning a status, meaning exactly the same thing whether or not files are
  being captured.
- The output survives the call, so a `post` block can read it *after* the stage failed - which is
  the thing a return value fundamentally cannot do.
- Reporting can be attached with `post { always { ... } }` rather than `failure`, which matters for
  the motivating consumer: `adv5-config-val`'s exit code only reflects Error-severity findings, so a
  Warning-only run exits `0` and still has stderr worth reporting. `failure` alone would miss it;
  this is the reason notifying and failing are separate conditions at all.
- Either stream can be requested alone. `adv5-config-val`'s stdout is just per-validator progress
  noise, so the consumer asks for `stderrFile` only and stdout is left entirely untouched.

The cost is that the caller does its own `readFile` (two lines, and it should pin
`encoding: 'UTF-8'` there, since .NET tools emit UTF-8 and findings plausibly contain non-ASCII
content), and that the files linger in the workspace. Both are acceptable: the workspace is CI
scratch space, and a lingering capture file is occasionally useful to `archiveArtifacts` on a bad
run.

**The caller supplies the paths; the library derives no filenames of its own.** Deriving them (from
`toolBinary`, the stage name, or anything else) would need a sanitizer for shell-unsafe characters,
would leave a collision window whenever the derived key repeated in one workspace, and - decisively -
could not be reconstructed by the caller from the place it most needs to: `env.STAGE_NAME` does not
hold the same value inside a `post` block as inside that stage's `steps`, so a caller recomputing a
stage-keyed path from `post` would compute the wrong one. A caller-supplied path has none of these
problems, and a caller running the same tool twice in one workspace just names two files.

**The capture files are truncated (`: > "<file>"`) at the top of the script, before the
`DOTNET_CLI_HOME` guard, and after `umask`.** This is what makes a caller-owned file safe to read
unconditionally. Jenkins workspaces are reused between builds (and this consumer's pipeline sets
`skipDefaultCheckout()`), so without truncation a run that exits *before* the tool ever starts - the
`DOTNET_CLI_HOME` guard, or `mkfifo ... || exit 125` - would leave an *earlier* build's file in place
for the caller to read back and report as this run's findings. Stale findings notified as current are
worse than no notification, so the truncation has to precede any early exit; hence the
`preGuardLines` parameter on `scriptPreambleLines()` rather than appending after the guard. It lands
*after* `umask` so the files get the caller's intended permissions - confirmed by real execution
(`umask 002` → `-rw-rw-r--`). Verified by real execution that with a pre-seeded stale file and a
failing `DOTNET_CLI_HOME` guard, the file the caller would read is 0 bytes.

**Capture paths are validated and rejected, never sanitized.** The caller names the file and reads
that exact path back, so silently rewriting it into a safe variant would be a worse failure than
refusing: the caller's `readFile` would fail, or worse find a stale file at the path it asked for. So
quotes, `$`, backticks, backslashes and newlines (which would expand or break out of the generated
script's double-quoted paths), absolute paths and `..` segments (which could be written but never read
back, since `readFile`/`fileExists` resolve against the workspace), and naming one file for both
streams (which would interleave the streams and collide both FIFOs on one path) are each a call-time
`IllegalArgumentException`. Relative paths with directory segments (`build/reports/err.log`) are
explicitly allowed.

**Only the FIFOs are cleaned up, in a Groovy-side `finally` wrapped in its own try/catch.** The
capture files must outlive the call - that's the point - so cleanup covers only the FIFOs, which are a
transient internal conduit nothing downstream should ever see. It has to happen on the Groovy side
because the generated script's own trailing `rm -f` only covers a clean run through the whole script:
an abort, a kill, or an early death under Jenkins' default `sh -e` skips it, confirmed by real
execution to leak the FIFO paths into the workspace. The `finally` therefore removes them itself,
unconditionally - the only place cleanup is guaranteed to run however the script terminated. Its
try/catch matters because a `finally` block that itself throws replaces whatever exception was already
propagating: with the default `returnStatus: false`, a nonzero tool exit *is* such an exception, so a
cleanup failure that replaced it would mask the actual reason the build failed behind a stray-FIFO
problem.

**Bash, not POSIX `sh`, for the capture script - unconditionally, not gated behind `loginShell` like
`shScript()`'s shebang.** Named FIFOs, background jobs, and `$!`/`wait <pid>` are all POSIX, not
bash-specific, so this mechanism *could* run under plain `sh` (e.g. dash). The unconditional
`#!/bin/bash` is kept for consistency with the rest of this class's unix-path conventions (which
already assume bash is available, e.g. for `loginShell`), not because this specific mechanism demands
it - though *some* shebang is required regardless, see below. Shared with `shScript()` via
`scriptPreambleLines(..., forceBash)` rather than a second, duplicated preamble (see below).

**Verification.** Unit tests assert on the generated script string, not on real bash execution
semantics (a Groovy mock stands in for `jenkins.sh`) - they cannot by themselves catch a subtle
real-shell bug like the `wait`/process-substitution or fd-inheritance ones above. Those were
confirmed, and the mechanism confirmed correct, by literally
running each generated script shape with a real bash (5.3, matching Linux Jenkins agents far more
closely than macOS's frozen bash 3.2) against a fixture tool that prints to both streams with
deliberate delays between lines and exits non-zero - across repeated runs, with timestamps
confirming output appeared progressively rather than being buffered, and (for the `wait` bug
specifically) a deliberately slowed-down `tee` proving the difference between "returns immediately"
(process substitution) and "genuinely blocks for the full delay" (FIFO + background job). Local bash
is not a full substitute for Jenkins' own Durable Task Plugin process handling, so a real-Jenkins run
is still worth doing once this is deployed - mirroring the separate real-Jenkins verification
`serialize-dotnet-tool-install` had.

**Only the requested streams get a FIFO and a `tee`.** An omitted `stdoutFile`/`stderrFile` means no
FIFO, no `tee`, no redirection and no PID in the `wait`/watchdog lists for that stream - it reaches
the console through the shell's own inherited descriptor, exactly as on the non-capturing path.
Verified by real execution for the stderr-only shape (exit code propagated, stderr split into the
file and the console, stdout untouched, no stdout file created). Keeping `exec 3>&1 4>&2` and the
command's `3>&- 4>&-` unconditional even in the single-stream case is deliberate: it costs nothing,
and it keeps the both-streams shape - the one the cross-contamination fix was verified against - as
the single code path rather than a special case.

**`loginShell`/`umask`/`logCommandToStdErr`/`stdoutFile`/`stderrFile` are grouped into a single
`options` Map parameter on `runTool()`, rather than more trailing positional parameters.**
`runTool()` already had three trailing booleans/strings before this change; a fourth pushed real call
sites past readable (`runTool("MyTool", "mytool", [], null, false, false, null, false, true)`) and the
trend would only worsen with any future unix-only knob - as this change itself demonstrates, having
ended up adding two options rather than one. Grouping them while this is the only PR touching the
signature is cheaper than doing it later once more callers exist. `vars/runDotnetTool.groovy`'s
public Map-based API is unaffected - this only changes the internal `Dotnet.runTool()` signature and
its direct callers (the var wrapper, and this project's own tests).

**A capture file requested on Windows throws immediately, rather than silently having no effect.**
`loginShell`/`umask`/`logCommandToStdErr` already have documented "no effect on Windows" behavior,
which is harmless because they only change how the command is invoked. A capture file is different:
the caller named it and is about to `readFile` it, so silently producing no file turns a clear
call-time error into a missing-file failure in whatever `post` block reads it - or, on a reused
workspace, into an earlier build's file being read back as this run's output.

**A stale artifact at either FIFO path (from a previous crashed/killed run) is removed immediately
before `mkfifo`, not left for `mkfifo` to fail on.** Confirmed by real execution that this matters
specifically when the stale artifact is a *regular* file rather than no file or a stale FIFO:
`mkfifo` erroring on an existing FIFO would at least be a loud failure, but a stale regular file at
that path breaks capture silently instead - `tee` reading from a regular file hits EOF immediately
and exits, then `<command>`'s own redirect writes straight into that now-unpiped file, giving no
capture, no live console passthrough, and an exit code that still looks unremarkable.

**A background watchdog bounds how long `wait` can block on a hung/lingering child of the tool.**
Because `wait` is a real barrier here (see the `tee`/FIFO decision), a child of `<command>` that
outlives it while still holding the inherited stdout/stderr - so the corresponding `tee` never sees
EOF - would block it forever, confirmed by real execution. A background job (`( sleep <timeout>; kill <tee pids> ) &`) is started right before `wait`, killed
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

**The forced shebang escapes Jenkins' default `sh -xe`, and this is load-bearing, not stylistic.**
Easy to mistake for a mere consistency choice, since the FIFO mechanism itself (`mkfifo`, background
jobs, `$!`/`wait <pid>`) is POSIX rather than bash-specific. But confirmed by real execution that this
exact script shape, run *without* any shebang under `sh -e` (what Jenkins uses when no custom shebang
overrides it), dies the instant `<command>` exits non-zero - before `_exit_code=$?`, `wait`, or any
cleanup ever runs, leaking both FIFOs and reducing the capture to whatever `tee` happened to flush in
time, i.e. exactly the race the FIFOs exist to prevent. *Some* shebang - not specifically bash - would
suffice to escape `-e`; bash specifically is kept for consistency with `loginShell`'s own bash
requirement elsewhere in this class, layered on top of the escape-`-e` requirement, not instead of it.

## Risks / Trade-offs

- **[Two capturing calls naming the same file in one workspace overwrite each other]** Accepted: the
  names are entirely the caller's own choice, so a caller running the same tool twice just names two
  files, and the failure mode is visible in the Jenkinsfile rather than hidden in the library.
- **[The capture files persist in the workspace]** Deliberate - a `post` block has to be able to read
  them after the call returned or threw, and the files are the caller's, so the library cleaning them
  up would defeat the feature. The cost is workspace litter on CI scratch space, and it is bounded:
  the files are truncated at the start of every run, so they don't grow across builds. A caller that
  cares can `archiveArtifacts` them or delete them in `post { cleanup { ... } }`.
- **[A caller must handle an empty capture file]** The file is truncated up front and may legitimately
  end up empty - a tool that wrote nothing to that stream, or a run that exited before the tool
  started. Callers must treat empty as "nothing to report" rather than an error, and should guard with
  `fileExists` for the pathological case where the script died before even the truncation ran.
  Accepted: getting this wrong yields a missing notification, not a wrong build result.
- **[Separate-stream capture depends on a tool's own stdout/stderr split being meaningful]** If a
  tool dumps everything to stdout (as `adv5-config-val` does today, before the follow-up change
  tracked in `tasks.md`), the `stderrFile` is simply empty and offers no benefit over the combined
  approach for that tool specifically — the caller asks for `stdoutFile` too and is back to the
  original noise problem until the tool itself is updated. This is an accepted, visible trade-off
  (an empty file is an obvious signal to fall back, not a silent wrong answer), not a flaw in the
  capture mechanism itself.
- **[Lost cross-stream ordering]** Per the Non-Goals above; accepted, no current consumer needs
  it.
- **[A hung/lingering child truncates output after the watchdog timeout, rather than blocking
  forever]** A genuine trade-off, not a free fix: if `<command>`'s output was still being written
  when the watchdog fires, whatever hadn't been flushed yet is lost from the capture (though the
  live console already saw it as it happened, and the caller reads a truncated file with no signal
  that it was truncated). Accepted because the alternative - blocking `wait`
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
  document. Worth specifically checking whenever this runs on a real agent, not just re-confirming
  the already-verified parts.
- **[A watchdog killing an already-exited tee's PID could theoretically hit a reused PID under the
  same agent user, in the narrow case where only one of the two tees lingers]** Accepted, not worth
  building anything for: the window is the watchdog's own timeout (300s), the failure is a
  suppressed error (`2>/dev/null` on the `kill`) rather than anything observable going wrong, and
  no current or plausible near-term consumer's usage pattern makes this likely enough to justify
  machinery narrower than "accept it."
- **[`Dotnet.runTool()`'s signature change from positional trailing parameters to a single
  `options` Map is source-breaking for any caller using the old positional form directly]** This is
  *not* the same claim as "purely additive" made elsewhere in this document about the new options
  themselves - that claim is still true for the public `runDotnetTool` step, whose Map-based call
  signature and return shape didn't change at all. `Dotnet.runTool()` is a different matter: its
  parameter list actually changed shape, which breaks source compatibility for any caller still
  using the pre-existing positional form. Verified via an org-wide `gh search code` (see Migration
  Plan) that no such caller exists today, in this repo or any other `wooga` repo - but `Dotnet` is a
  public class other repos could import and call directly (`Dotnet.fromJenkins(this, ...)`), so
  "no current caller" is a fact established by searching, not a property of the change being
  "internal" by design. Re-verify with the same search before merging if meaningful time passes
  between this check and the actual merge.

## Migration Plan

The public `runDotnetTool` step is purely additive: its Map-based call signature and return shape are
unchanged for every existing caller, and unchanged even for callers that *do* pass `stdoutFile`/
`stderrFile`, so nothing about this change can surprise a caller with a different return type. No
rollback concerns beyond reverting this change, no data migration.

`Dotnet.runTool()` itself is not purely additive - see the matching Risks bullet above for why, and
for what was actually verified (an org-wide `gh search code`) about whether a direct caller of the
old positional form exists today. In short: no caller was found anywhere outside this library's own
`vars/runDotnetTool.groovy` and its test file, both already updated.

Separately, `runDotnetTool` (the *public* step, whose Map-based call signature and return shape
did not change at all) has exactly one other real external caller found via the same search:
`wooga/adventure5-jenkins-pipeline`'s `vars/adventure5Tools.groovy` (a shared step used across
content-build pipelines, not just this configs one), which calls the Map form with
`loginShell`/`logCommandToStdErr`/`umask`/`returnStatus` and does not capture output -
unaffected either way.

## Open Questions

- Should a future increment add Windows (`bat`) support? Deferred — no current consumer needs it; can
  be scoped as its own change if that ever changes. Since the return shape doesn't differ between the
  capturing and non-capturing paths, such an increment would only have to solve
  duplicate-to-a-file-while-still-streaming-live for `bat`, with no contract to match.
