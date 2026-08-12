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

- [ ] 7.1 In `adventure5-configs`'s `Wooga.Adv5.Configs.Validation` project, change
      `Program.cs`'s `PrintValidationMessages` to write via `Console.Error.WriteLine` instead of
      `Console.WriteLine`, so actual findings go to stderr; leave the per-validator
      `"Executing validator: X"` progress line in `RunValidations` on stdout, unchanged. This is a
      small, independently-justified change (correct stream usage for a CLI tool) that happens to
      also be exactly what this feature needs — not a Slack- or Jenkins-specific change to the
      tool.
- [ ] 7.2 Once `captureOutput` is merged and released under the `1.x` line, update
      `adventure5-tools`'s `configs/release_configs_to_sbs/Jenkinsfile` to run "Validate Configs"
      with `captureOutput: true`, then decouple notifying from failing — the two are not the same
      condition, since `Program.cs`'s exit code only reflects Error-severity messages
      (AD-36506), while a Warning-only run still has content worth reporting on `stderr` despite
      exiting `0`:
      - Notify whenever `result.stderr` is non-empty, forwarding it verbatim (no text
        filtering/parsing) to `slack:notify` — this covers both warnings-only and error runs, and
        preserves the original PR #333 behavior of notifying on any message, not just failures.
      - Separately, fail the stage only when `result.exitCode != 0` (i.e. call `error(...)` at
        that point), independent of whether a Slack notification was sent — this preserves the
        existing warning-vs-error build-abort semantics untouched.
      - This replaces the Slack integration added directly to `adv5-config-val`
        (wooga/adventure5-configs#333) and the generic `post { failure {...} } }` duplication
        issue on wooga/adventure5-tools#449.
