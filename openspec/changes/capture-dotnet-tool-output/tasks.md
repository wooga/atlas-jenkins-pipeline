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
