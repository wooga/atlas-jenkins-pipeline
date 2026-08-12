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
- Streaming captured output live to the console as it's produced. The file-redirect approach
  described below only reads each file back after the command exits; a caller still sees the
  tool's normal output in the Jenkins console log as it runs (redirecting a file descriptor
  inside the script doesn't remove Jenkins' own console capture of the underlying process), so
  nothing is lost for a human watching the build live — this Non-Goal is about not building a
  second, separate live-tailing mechanism on top of that.
- Interleaving/reordering stdout and stderr relative to each other. Capturing them into separate
  files means any information about *when*, relative to each other, a given stdout line and a
  given stderr line were printed is lost — each stream is returned as its own ordered text, not
  merged. No current consumer needs cross-stream ordering; a tool that cares about this should
  encode order within a single stream itself (e.g. structured output on one stream only).

## Decisions

**Capture stdout and stderr into two separate files, not one combined stream.** Reverses the
combined-capture approach from the earlier draft of this design (see Context) specifically to
avoid coupling a caller's parsing logic to a tool's message-formatting choices. Two `>`/`2>`
redirects in the generated script (`${command} > ${stdoutFile} 2> ${stderrFile}`) cost nothing
extra over one; the caller gets both back and picks whichever it needs, or both.

**Capture via file redirection inside the generated script, not `sh`'s own `returnStdout`.**
Since `sh(returnStdout: true, returnStatus: true)` isn't a legal combination, and we need both
the text and a non-throwing exit code in one call, the script itself redirects its own output
and the call uses `returnStatus: true` under the hood; each file is then read back with
`jenkins.readFile()`. This keeps `sh`'s own step contract untouched and puts the "give me status
*and* text" behavior entirely inside `Dotnet.groovy`, where it can be tested directly against the
generated script string, rather than depending on an assumption about how Jenkins' `sh` step
might evolve.

**Deterministic, `toolBinary`-keyed file names, not random ones.** `UUID.randomUUID()` and
`Math.random()` aren't safely callable inside the Jenkins CPS sandbox without extra script
approval (the same category of restriction noted for other steps in this library). Names derived
from `toolBinary` (e.g. `.dotnet-tool-stdout-${toolBinary}.log`/`.dotnet-tool-stderr-${toolBinary}.log`,
workspace-relative) are simple, require no sandbox approval, and are unique across the tools that
would plausibly run concurrently in one workspace.

**Accepted risk: two concurrent `captureOutput` calls for the *same* `toolBinary` in the *same*
shared workspace can collide on these files.** Not addressed by this change. This mirrors the
`serialize-dotnet-tool-install` change's own workspace-relative NuGet-config lock, which accepted
an analogous scoping assumption ("this specific file is workspace-scoped, and two invocations
racing on it in the *same* workspace is the only failure mode, which the observed usage patterns
don't hit") rather than building a more general per-invocation-unique scheme for a race with no
known instance in current pipelines. If this is ever observed, the fix would be the same
`mkdir`-lock pattern already used twice in this file, not a bigger redesign.

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

## Migration Plan

Purely additive to an existing shared-library step's public API (`runDotnetTool`/`runTool` gain
one new optional parameter, default `false`); no consumer-facing change for any existing caller
that doesn't pass `captureOutput`, no rollback concerns beyond reverting this change, no data
migration.

## Open Questions

- Should a future increment add Windows (`bat`) support, given `bat`'s `returnStdout` doesn't
  have the same mutual-exclusion constraint as `sh`? Deferred — no current consumer needs it; can
  be scoped as its own change if that ever changes.
