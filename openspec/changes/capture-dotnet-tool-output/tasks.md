## 1. Implementation in Dotnet.groovy

- [x] 1.1 Add `toolStdoutFile(toolBinary)`/`toolStderrFile(toolBinary)`, each returning a
      workspace-relative, deterministic path (`.dotnet-tool-stdout-${toolBinary}.log`/
      `.dotnet-tool-stderr-${toolBinary}.log`) — not random/UUID names, since those aren't safely
      usable inside the Jenkins CPS sandbox without extra script approval.
- [x] 1.2 Add `captureOutputScriptSh(command, stdoutFile, stderrFile, loginShell, umask, logCommandToStdErr)`:
      an unconditional `#!/bin/bash` shebang (process substitution requires real bash, not POSIX
      `sh`), the `set -x`/`umask`/`DOTNET_CLI_HOME`-guard preamble, `exec 3>&1 4>&2` to save the
      original stdout/stderr, `${command} > >(tee "${stdoutFile}" >&3) 2> >(tee "${stderrFile}" >&4)`
      so output is duplicated to the capture files *and* still streams live to the console,
      `_exit_code=$?` captured immediately, `exec 3>&- 4>&-` + `wait` so the `tee` subshells finish
      flushing before the script exits, then `exit $_exit_code`. Revised from an initial plain
      `>`/`2>` redirect version after real-execution testing showed that version made the run go
      silent in Jenkins and (separately) cross-contaminated the two streams — see `design.md`.
- [x] 1.3 Add `runTool(...)` parameter `Boolean captureOutput = false`; validate at the top of the
      method (mirroring `validateSelectors`/`validateNugetConfig`'s guard-clause style) that
      `captureOutput` and `returnStatus` aren't both `true`, and that `captureOutput` isn't `true`
      on a non-unix agent — both throw `IllegalArgumentException` with a clear message.
- [x] 1.4 Wire the unix branch: when `captureOutput`, run via
      `jenkins.sh(label: command, script: captureOutputScriptSh(...), returnStatus: true)`, then
      `jenkins.readFile(stdoutFile)`/`readFile(stderrFile)`, then remove both files (`jenkins.sh(
      script: "rm -f ${stdoutFile} ${stderrFile}", returnStatus: true)`) in a `finally` so
      they're removed whether the reads succeed or not; return
      `[exitCode: <status>, stdout: <text>, stderr: <text>]`.
- [x] 1.5 Leave the existing non-capturing unix branch and the Windows `bat` branch (aside from
      the new upfront validation throwing before either branch is reached) unchanged.

## 2. vars/runDotnetTool.groovy and docs

- [x] 2.1 Forward `args.captureOutput` from the `call(Map args)` overload into
      `dotnet.runTool(...)`, alongside the existing `returnStatus` forwarding.
- [x] 2.2 Update `vars/runDotnetTool.txt` to document `captureOutput`, its unix-only scope, its
      mutual exclusivity with `returnStatus`, and the `[exitCode, stdout, stderr]` return shape —
      including that the library makes no requirement on how a tool splits its own output
      between the two streams.

## 3. Tests

- [x] 3.1 `DotnetSpec`: "runTool captures stdout, stderr, and exit code separately when the tool
      fails" — asserts the returned Map's `exitCode` matches a non-zero fixture exit, `stdout`
      contains fixture stdout text, and `stderr` contains fixture stderr text, from a fixture that
      writes distinct content to each stream.
- [x] 3.2 `DotnetSpec`: "runTool captures stdout/stderr when the tool succeeds" — exit code `0`,
      both streams still returned.
- [x] 3.3 `DotnetSpec`: "runTool returns an empty stderr string for a tool that only writes to
      stdout" (and the symmetric stdout-empty case) — confirms streams aren't cross-contaminated.
- [x] 3.4 `DotnetSpec`: "runTool removes both captured-output files after a failing run" and after
      a succeeding run — assert no lingering files/cleanup command ran in both cases.
- [x] 3.5 `DotnetSpec`: "runTool rejects captureOutput combined with returnStatus" — asserts
      `IllegalArgumentException`.
- [x] 3.6 `DotnetSpec`: "runTool rejects captureOutput on a non-unix agent" — asserts
      `IllegalArgumentException` without attempting the Windows `bat` path.
- [x] 3.7 `RunDotnetToolSpec`: asserts `captureOutput` is forwarded from the var's Map-form call
      into `Dotnet.runTool`.
- [x] 3.8 Run the full test suite to confirm no regressions in existing `runTool`/Windows-bat/
      `returnStatus` coverage. 478 tests, all pass except `CacheSpec > renews project cache with
      valid parameters`, which fails identically on `master` with none of this change's commits
      applied (confirmed via `git stash`) — pre-existing, unrelated to `Dotnet`/`runDotnetTool`.

## 3a. Review fixes (PR #353, first review round)

Real, substantive review — not a rubber stamp. All findings verified before being accepted;
none dismissed. Two were confirmed real bugs via real bash execution, not just re-reading the
code: the `wait` claim (§1) was reproduced with a deliberately slowed-down `tee`, and the fix was
independently verified to actually block.

- [x] 3a.1 **Critical: `wait` does not synchronize the tee subshells on any bash version.**
      Process-substitution subshells are never added to the job table, so a bare `wait` returns
      immediately regardless of whether `tee` has finished. Confirmed by reproducing the
      reviewer's exact test (a `sleep 2` before `tee`) on both bash 3.2 and 5.3 — `wait` returned
      in ~0.01–0.03s, file did not exist. Fixed by switching to named FIFOs with `tee` run as a
      real background job (`tee file >&N < fifo &` + `$!`), so `wait "$pid"` has an actual PID to
      block on — confirmed fixed the same way (2s wall-time, complete file, correct exit code, on
      both bash versions). `captureOutputScriptSh()` rewritten accordingly; `Dotnet.groovy`/
      `design.md` corrected to stop claiming the process-substitution version worked.
- [x] 3a.2 **Critical: `readFile()` throws when the script exits before the command ever ran**
      (e.g. `requireDotnetCliHomeSh()`'s own guard failing). Fixed: guard both `readFile()` calls
      with `jenkins.fileExists(...)`, returning `""` when the file was never created, so
      `captureOutput` genuinely never throws on a bad exit as documented.
- [x] 3a.3 `rm -f` cleanup now quotes both file paths (a `toolBinary`/stage name containing a
      space would otherwise produce files the unquoted cleanup silently missed).
- [x] 3a.4 `readFile()` now pins `encoding: 'UTF-8'` explicitly rather than trusting the agent's
      platform-default encoding, since .NET tools emit UTF-8 and validation messages plausibly
      contain non-ASCII content.
- [x] 3a.5 `vars/runDotnetTool.txt` now documents that `captureOutput` always drops Jenkins'
      default `-x` tracing (since it always forces its own shebang), same as `loginShell` already
      documents for itself — pair with `logCommandToStdErr: true` to get it back.
- [x] 3a.6 The cleanup `sh(rm -f ...)` call is now wrapped in its own try/catch, so a cleanup
      failure can never mask a real exception already propagating from the capturing call (e.g.
      an agent disconnect) behind an unrelated cleanup error.
- [x] 3a.7 Capture file names now also key on `env.STAGE_NAME` when available (not just
      `toolBinary`), narrowing the accepted same-workspace collision risk to "same tool, same
      stage, same workspace" rather than "anywhere in the workspace" — cheap insurance against a
      `parallel {}` block validating two config sets on one workspace, not a known current
      pattern but a plausible future one.
- [x] 3a.8 `scriptPreambleLines()` and `captureOutputScriptSh()` now share the preamble via a
      `forceBash` parameter, rather than duplicating the shebang/PATH-export/`set -x`/`umask`/
      `DOTNET_CLI_HOME`-guard lines — a future preamble addition now applies to both script
      variants automatically instead of risking one being updated and the other forgotten.
      (Conceded: the original "duplication is simpler than a boolean-parameterized helper"
      rationale, borrowed from the lock-preamble precedent's *stale-timeout-branch* complexity,
      didn't actually apply to a single shebang toggle.)
- [x] 3a.9 `runTool(...)`'s `loginShell`/`umask`/`logCommandToStdErr`/`captureOutput` parameters
      collapsed into a single `Map options = [:]` parameter, fixing the "9 positional params, 5
      booleans, unreviewable at a glance" call sites (e.g. `runTool("MyTool", "mytool", [], null,
      false, false, null, false, true)`). `vars/runDotnetTool.groovy` and every existing
      `DotnetSpec`/`RunDotnetToolSpec` call site updated to the Map form for options beyond
      `returnStatus`; calls not using any of the four options are unaffected.
- [x] 3a.10 New `DotnetSpec` test: cleanup still runs, and the real exception still propagates
      (not masked), when the capturing `sh()` call itself throws (e.g. simulating an agent
      disconnect) — covers the "sh()/readFile() calls themselves threw" case the existing cleanup
      comment claimed but nothing tested.
- [x] 3a.11 New `DotnetSpec` test: the missing-capture-file path (§3a.2) returns
      `[exitCode: <n>, stdout: "", stderr: ""]` rather than throwing.
- [x] 3a.12 Full suite re-run after all fixes: 481 tests, same single pre-existing unrelated
      `CacheSpec` failure (confirmed via `git stash` against `master`, and confirmed non-flaky by
      re-running 3x). One transient, non-reproducing batch of 17 `JavaCheckSpec` failures on one
      run turned out to be pre-existing test-suite flakiness unrelated to this change — did not
      reproduce on 3 subsequent full-suite runs, nor when run isolated or paired with the changed
      specs.
- [ ] 3a.13 Not addressed, deliberately deferred rather than dismissed: `RunDotnetToolSpec`'s
      `captureOutput` test still only asserts on the generated script/cleanup command, not the
      returned Map, since this harness's `withEnv` mock doesn't thread a wrapped closure's return
      value through (confirmed separately, not part of this review). `DotnetSpec`'s pure-Groovy-mock
      tests remain the only coverage of the actual return shape — acceptable given the layering
      (integration-level `RunDotnetToolSpec` vs. unit-level `DotnetSpec`), but noted rather than
      silently accepted.
- [x] 3a.14 Verified the blast radius of §3a.9's `Dotnet.runTool()` signature change org-wide, not
      assumed: `gh search code` across every `wooga` repo for both
      `net.wooga.jenkins.pipeline.model.Dotnet` and `runTool(` found no caller anywhere outside
      this library's own `vars/runDotnetTool.groovy` and its test file - both already updated.
      `Dotnet` is reachable only through the `vars/` steps; nothing external imports or calls it
      directly. Separately confirmed the *public* `runDotnetTool` step (whose Map-based signature
      and return shape didn't change) has exactly one other real external caller org-wide -
      `wooga/adventure5-jenkins-pipeline`'s `vars/adventure5Tools.groovy`, a shared step used
      across content-build pipelines - which doesn't use `captureOutput` and is unaffected either
      way. See `design.md`'s Migration Plan.

## 3b. Review fixes (PR #353, second review round)

Five new findings introduced by the first round's own rework, plus doc drift. All verified by
real execution before being accepted, same discipline as the first round - two of these
(`STAGE_NAME`'s `/` case, the stale-regular-file case) produce a *silently wrong answer*
(`[exitCode: 1, stdout: "", stderr: ""]` indistinguishable from a genuine failure) rather than a
loud error, which is the worst failure shape for something a Slack notification depends on.

- [x] 3b.1 **`STAGE_NAME` interpolated unsanitized into filenames.** A `/` in a stage name breaks
      `mkfifo` (no such directory) and produces the silent-wrong-answer shape above; `$`/backtick
      expand inside the generated script's double-quoted paths, an injection surface. Confirmed
      with `Validate/Configs`, `Deploy $HOME`, and a backtick case. Fixed: `stageKeySuffix()` now
      sanitizes via `replaceAll(/[^A-Za-z0-9._-]/, '_')`.
- [x] 3b.2 **A hung/lingering child can now hang `wait` forever - the flip side of fixing §3a.1.**
      The process-substitution version's `wait` was a no-op, so it *couldn't* hang; the FIFO fix
      makes `wait` a real barrier, which is correct but introduces this as a genuinely new risk.
      Confirmed by real execution (a child backgrounding `sleep 30` after the main tool exits)
      that `wait` blocks indefinitely. Fixed with a background watchdog
      (`CAPTURE_OUTPUT_WATCHDOG_TIMEOUT_SECONDS`, 300s) that kills both `tee` PIDs if `wait` hasn't
      returned by then, degrading to truncated output rather than hanging the step. Confirmed by
      real execution to unblock the hung case correctly and add no delay to the normal case.
- [x] 3b.3 **The `.fifo` files escaped the Groovy-side cleanup**, contradicting the spec's "no
      longer exist in the workspace once the call returns" scenario on any abnormal termination
      (the script's own trailing `rm -f` only runs on a clean path through the whole script).
      Fixed: the Groovy `finally` now removes both `.fifo` paths alongside the two `.log` files.
- [x] 3b.4 **A stale *regular* file at a `.fifo` path silently breaks capture and console output
      both**, confirmed by real execution (`tee` hits EOF immediately reading a regular file and
      exits; the tool's own write then goes straight into that now-unpiped file - no capture, no
      live passthrough, unremarkable exit code). Fixed: `rm -f` on both FIFO paths immediately
      before `mkfifo`, removing any leftover artifact from a previous crashed/killed run - this
      also happens to be what made §3b.3's `mkfifo: File exists` console noise on a stale FIFO
      (not just a stale regular file) go away too.
- [x] 3b.5 **The forced-bash-shebang comment claimed it was "purely for consistency," which is
      wrong in a load-bearing way.** Confirmed by real execution: this script shape, run without
      any shebang under `sh -e` (Jenkins' default when no shebang overrides it), dies the instant
      the tool exits non-zero - before `_exit_code=$?`, `wait`, or cleanup ever run, reverting the
      whole mechanism to winning-by-luck (exactly what the FIFO fix in §3a.1 exists to prevent) and
      leaking the FIFOs. Reworded the comment and `design.md`'s matching Risks bullet: the shebang
      escapes Jenkins' `-e`, independently of the FIFO mechanism's own POSIX-not-bash-specific
      nature; bash specifically (rather than any shebang) is the part that's merely for
      consistency.
- [x] 3b.6 Doc drift: `design.md`'s Migration Plan corrected to stop claiming `Dotnet.runTool()`
      itself is "purely additive" - its signature changing from positional to a Map `options`
      parameter is source-breaking for a direct positional caller, distinct from the genuinely
      additive `captureOutput` option and the unchanged public `runDotnetTool` step (§3a.14 already
      covers the org-wide verification that no such caller currently exists). Added a Javadoc note
      on `runTool()` warning that Groovy's bare trailing named-argument sugar
      (`runTool(p, b, a, v, false, captureOutput: true)`, no brackets) does not reach the trailing
      `options` parameter - it always collapses into a Map passed as the *first* argument instead,
      confirmed by real execution to throw `MissingMethodException` rather than silently doing the
      wrong thing; every call site here already uses the explicit `[captureOutput: true]` literal
      form for this reason. `runDotnetTool.txt` reworded from "redirects... to their own temp
      files" to "duplicates... while still streaming live to the console" - the previous wording
      undersold the one thing a pipeline author most wants reassurance about.
- [x] 3b.7 New tests: `stageKeySuffix()` had zero coverage before this round (every existing
      capture test ran with no `STAGE_NAME` set). Added one test asserting the suffix appears when
      set, and one `@Unroll` test (3 cases: `/`, `$`, backtick) asserting each unsafe character is
      sanitized. Also added watchdog-line and rm-before-mkfifo assertions to the existing
      script-content test, which previously didn't cover either.
- [x] 3b.8 Full suite re-run after all fixes: 485 tests, same single pre-existing unrelated
      `CacheSpec` failure.

## 3c. Review fixes (PR #353, third review round)

One new finding, introduced by §3b.2's own watchdog fix, plus two nits.

- [x] 3c.1 **`kill "$_watchdog_pid"` alone leaks an orphaned `sleep` process on the normal
      (non-hung) path.** It terminates the watchdog subshell but not the `sleep` it's blocked
      inside, which gets orphaned and lives out its full `CAPTURE_OUTPUT_WATCHDOG_TIMEOUT_SECONDS`
      (300s). Confirmed by real execution - worse than initially reported: every one of 5
      consecutive runs left a live, `ppid`-1 `sleep 300` process behind on bash 5.3. Fixed:
      `pkill -P "$_watchdog_pid" 2>/dev/null; kill "$_watchdog_pid" 2>/dev/null` (kills the
      subshell's child first, then the subshell) - confirmed by real execution across 5 more runs
      to leave nothing behind, on both bash 3.2 and 5.3. `pkill -P` isn't POSIX but is present on
      both Linux and macOS agents. Added a `design.md` Risks bullet flagging that the
      lingering-child scenario itself (does the watchdog actually fire and unblock cleanly under
      Jenkins' real Durable Task Plugin, not just local bash) is still unverified on real Jenkins -
      tied to the already-planned real-Jenkins run in §6.2, not a new task.
- [x] 3c.2 Nit: the watchdog script-content test assertions hardcoded the literal `sleep 300`
      instead of interpolating `Dotnet.CAPTURE_OUTPUT_WATCHDOG_TIMEOUT_SECONDS` (a public
      `static final` constant) - a future change to the constant would fail the test with a
      string-diff instead of the test tracking it. Fixed in both `DotnetSpec` and
      `RunDotnetToolSpec` (the latter needed a new `import net.wooga.jenkins.pipeline.model.Dotnet`,
      being in a different package).
- [x] 3c.3 Nit: `design.md` stated the `Dotnet.runTool()` signature-break point three times (a
      Risks bullet, then two near-identical Migration Plan paragraphs). Collapsed Migration Plan to
      one sentence pointing at the Risks bullet plus the concrete search result, instead of
      restating the full rationale twice.
- [x] 3c.4 Full suite re-run: 485 tests, same single pre-existing unrelated `CacheSpec` failure.

## 3d. Review fixes (PR #353, fourth review round)

Three findings, one genuine gap and two hardening improvements, plus one accepted micro-edge with
no action taken.

- [x] 3d.1 **`toolBinary` interpolated unsanitized into the capture filenames** - the same
      reasoning that motivated sanitizing `STAGE_NAME` (§3b.1) applies verbatim, since both end up
      in the same shell-embedded double-quoted paths. Fixed: extracted a shared
      `sanitizeForFilename()` helper, used by both `toolStdoutFile()`/`toolStderrFile()` (for
      `toolBinary`) and `stageKeySuffix()` (for `STAGE_NAME`). Deliberately scoped to the filename
      derivation only - `toolBinary` still (correctly) appears raw in the `dotnet tool run
      <toolBinary>` invocation itself, which predates `captureOutput` and applies equally to the
      non-capturing path, not a new exposure. Added a two-case `@Unroll` test (`/`, `$`).
- [x] 3d.2 Hardening: `mkfifo ... || exit 125` fails fast on a genuine setup failure (disk full,
      permissions) rather than letting it cascade into the tee/redirect chain and land in the same
      acknowledged indistinguishable-from-tool-failure shape. Confirmed by real execution (a
      directory obstructing one of the FIFO paths) that the script now stops immediately with a
      distinguishable exit code instead of proceeding. Possible only because the forced shebang
      already escapes Jenkins' default `sh -e` - noted in the comment as a reason not to
      "simplify away" that shebang later.
- [x] 3d.3 Hardening: the command's own invocation now explicitly closes fds 3/4
      (`3>&- 4>&-`), so neither it nor any child it spawns retains access to the saved console
      descriptors - confirmed by real execution that a child does inherit fd 3/4 by default without
      this. Directly narrows the one thing this change flags as plausible-but-unverified (whether a
      lingering child holding console descriptors could keep a real Jenkins step open) by removing
      this specific exposure outright, independently of how that question resolves; doesn't replace
      the watchdog, which backstops a different exposure (a child holding the FIFO's write end,
      fd 1/2, open).
- [x] 3d.4 Accepted, no action: a watchdog `kill` targeting an already-exited tee's PID could
      theoretically hit a reused PID under the same agent user, in the narrow case where only one
      of two tees lingers. Suppressed error, bounded by the watchdog's own timeout, no plausible
      current usage pattern makes it likely - not worth machinery narrower than accepting it. Noted
      in `design.md`'s Risks, not silently dropped.
- [x] 3d.5 Full suite re-run: 487 tests, same single pre-existing unrelated `CacheSpec` failure.

## 4. OpenSpec change artifacts

- [x] 4.1 `proposal.md` — why/what/capabilities/impact.
- [x] 4.2 `design.md` — separate-stream capture mechanism, file-naming, and mutual-exclusivity
      decisions with rationale (including why combined-stream capture was reconsidered).
- [x] 4.3 `specs/dotnet-tool-steps/spec.md` delta — ADDED requirement "Tool stdout and stderr can
      be captured separately, alongside its exit code" with scenarios.
- [x] 4.4 Validate the change (`openspec validate capture-dotnet-tool-output --strict`) before
      requesting review. Passes.

## 5. Review

- [ ] 5.1 Open an issue/Slack thread describing the proposed change ahead of the PR, per this
      repo's `.github/CONTRIBUTING.md` guidance — Raul Gigea (original author of
      `Dotnet.groovy`/`runDotnetTool`, GitHub handle `pletoss`) is on leave for two weeks; post
      async so he can weigh in when back without blocking this.
- [x] 5.2 Opened wooga/atlas-jenkins-pipeline#353 against `master`, marked ready for review.
      Note: `pletoss` (tagged in an earlier draft of the PR description as a prospective reviewer)
      is Raul himself, not a distinct maintainer — fixed to request review from `Joaquimmnetto`
      and `jhett12321` instead, both of whom have reviewed/merged in this exact area before.

## 6. Verification (real Jenkins)

- [x] 6.1 Local bash verification: ran the exact `tee`/fd3-fd4/`wait` script shape against a
      fixture tool (delayed prints to both streams, nonzero exit) with a modern bash (5.3,
      matching Linux Jenkins agents far more closely than macOS's frozen bash 3.2) — confirmed
      live output appears progressively (timestamped, matching injected delays), the correct exit
      code propagates, and stdout/stderr are captured without cross-contamination, across 5
      repeated runs. This caught two real bugs an initial version had (silent during the run;
      stream cross-contamination) that unit tests alone couldn't have caught, since they assert
      on the generated script string, not real shell execution.
- [ ] 6.2 Real-Jenkins run: once mergeable, trigger an actual pipeline using
      `runDotnetTool(..., captureOutput: true)` against a real tool on a real unix/macOS agent,
      and confirm the same three things (live console output, correct exit code, correctly split
      streams) hold there too — local bash isn't a full substitute for Jenkins' own Durable Task
      Plugin process handling, even though the script itself is agent-agnostic.

## 7. Downstream consumer

- [x] 7.1 Opened wooga/adventure5-configs#335: `Program.cs`'s `PrintValidationMessages` now
      writes via `Console.Error.WriteLine` instead of `Console.WriteLine`, so actual findings go
      to stderr; the per-validator `"Executing validator: X"` progress line in `RunValidations`
      stays on stdout, unchanged. A small, independently-justified change (correct stream usage
      for a CLI tool) that happens to also be exactly what this feature needs — not a Slack- or
      Jenkins-specific change to the tool. Independent of this PR; mergeable now. Manually
      smoke-tested (`2>/dev/null` shows only progress, `1>/dev/null` shows only findings); 138
      existing tests pass unmodified.
- [ ] 7.2 Opened wooga/adventure5-tools#452 as a **draft** (blocked on this PR merging and
      releasing under `1.x` — the Jenkinsfile's `@Library` floats on a released version, not this
      branch, so it genuinely can't be run or tested yet). Written now anyway per explicit
      request ("we can't test it but we can write it"). Updates `configs/release_configs_to_sbs/Jenkinsfile`'s
      "Validate Configs" stage to run with `captureOutput: true`, then decouples notifying from
      failing — the two are not the same condition, since `Program.cs`'s exit code only reflects
      Error-severity messages (AD-36506), while a Warning-only run still has content worth
      reporting on `stderr` despite exiting `0`:
      - Notify whenever `result.stderr` is non-empty, forwarding it (through a `shellSafeMessage()`
        escaping helper — backslash/quote/`$`/backtick — since the captured text isn't otherwise
        parsed or controlled and would otherwise risk breaking out of the shell-embedded
        `--message` string) to `slack:notify` — this covers both warnings-only and error runs, and
        preserves the original PR #333 behavior of notifying on any message, not just failures.
      - Separately, fail the stage only when `result.exitCode != 0` (i.e. call `error(...)` at
        that point), independent of whether a Slack notification was sent — this preserves the
        existing warning-vs-error build-abort semantics untouched.
      - This replaces the Slack integration added directly to `adv5-config-val`
        (wooga/adventure5-configs#333) and the generic `post { failure {...} } }` duplication
        issue on wooga/adventure5-tools#449.
