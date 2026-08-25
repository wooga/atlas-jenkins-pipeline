## ADDED Requirements

### Requirement: Tool stdout and stderr can each be duplicated into a caller-named file

The system SHALL provide `stdoutFile` and `stderrFile` options on `runDotnetTool`/`Dotnet.runTool`
that each name a workspace-relative file the corresponding stream is duplicated into (while still
passing that stream through live to the console). Either may be given independently of the other,
and an unrequested stream SHALL be left untouched. The files belong to the caller: the system SHALL
NOT read them, and SHALL NOT remove them, so that a later step or a `post` block can read them back
with `readFile` after the call - including after the tool failed. Passing either option SHALL NOT
change the step's own return/throw contract, which continues to be governed solely by
`returnStatus`. The system makes no requirement on how a given tool splits its own output between
the two streams - it duplicates whatever each requested stream already contains. This requirement
applies to unix/macOS agents only.

#### Scenario: Output still streams live to the console while being duplicated

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log")` against a tool that prints
  output with a delay between lines
- **THEN** each line appears in the live Jenkins console log as the tool prints it, not all at
  once only after the tool finishes

#### Scenario: Duplicating a failing tool's output

- **WHEN** a pipeline calls `runDotnetTool(packageId: "MyTool", toolBinary: "mytool", args: [...], stdoutFile: "out.log", stderrFile: "err.log")`
  and the tool exits non-zero while printing to both stdout and stderr
- **THEN** `out.log` contains exactly what the tool printed to stdout
- **AND** `err.log` contains exactly what the tool printed to stderr
- **AND** the call still fails the build on the tool's non-zero exit, exactly as it would without
  either option

#### Scenario: A caller that wants the exit code instead of a failure

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log", returnStatus: true)` and the
  tool exits non-zero
- **THEN** the call returns the tool's exit status without throwing
- **AND** `err.log` still contains what the tool printed to stderr

#### Scenario: Capturing one stream leaves the other untouched

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log")` without `stdoutFile`
- **THEN** `err.log` contains what the tool printed to stderr
- **AND** the tool's stdout reaches the console exactly as it would without either option, with no
  file created for it

#### Scenario: A tool that never writes to the captured stream

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log")` against a tool that prints
  everything to stdout and never writes to stderr
- **THEN** `err.log` exists and is empty, which the caller can treat as "nothing to report"

#### Scenario: Capture files survive the call so a post block can read them

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log")` and the tool exits non-zero,
  failing the stage
- **THEN** `err.log` still exists after the call, with its contents intact
- **AND** a `post` block on that stage can read it

#### Scenario: A leftover file from an earlier build is not read back as this run's output

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log")` on a reused workspace where
  a file already exists at that path from an earlier build, and this run exits before the tool's
  command ever runs (e.g. a missing `DOTNET_CLI_HOME`, or a failure setting up the capture
  mechanism)
- **THEN** the file the caller reads back is empty, not the earlier build's content

#### Scenario: An unsafe capture path is rejected rather than altered

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: <a path containing a quote, `$`, a
  backtick, a backslash, or a newline>)`
- **THEN** the call fails immediately with a clear error naming the offending option
- **AND** the path is not silently rewritten into a different one, since the caller will read back
  the exact path it named

#### Scenario: A capture path outside the workspace is rejected

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: <an absolute path, or one containing a
  `..` segment>)`
- **THEN** the call fails immediately with a clear error, since such a file could be written but
  not read back via `readFile`

#### Scenario: Naming one file for both streams is rejected

- **WHEN** a pipeline calls `runDotnetTool(..., stdoutFile: "both.log", stderrFile: "both.log")`
- **THEN** the call fails immediately with a clear error, rather than interleaving the two streams
  into one file unpredictably

#### Scenario: Capture files are unsupported on Windows

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log")` on a Windows agent
- **THEN** the call fails immediately with a clear error, rather than silently producing no file
  for a caller that is about to read one

#### Scenario: A cleanup failure does not mask a real failure from the capturing call itself

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log")` and the capturing call
  itself fails (the tool's own non-zero exit, or an unrelated reason such as an agent disconnect),
  and the subsequent internal cleanup also fails
- **THEN** the original failure propagates to the caller
- **AND** the cleanup failure is not what the caller sees

#### Scenario: A hung/lingering child of the tool does not block the call forever

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log")` against a tool that exits
  but leaves a child process running that still holds the inherited stdout/stderr open
- **THEN** the call still returns, rather than blocking indefinitely
- **AND** the capture file contains whatever was written before the wait was given up on, which may
  be truncated relative to what the lingering child eventually would have produced

#### Scenario: A stale artifact from a previous run at the same internal path does not silently break capture

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log")` and a file already exists at
  a path this call would use to set up its capture mechanism, left behind by a previous crashed or
  killed run
- **THEN** the stale artifact does not cause this call to silently capture nothing while still
  reporting an unremarkable exit code

#### Scenario: A failure setting up the capture mechanism itself is reported distinguishably

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log")` and setting up the capture
  mechanism fails for a reason unrelated to the tool itself (e.g. the agent's disk is full or
  permissions prevent creating a file at the capture path)
- **THEN** the call reports a distinguishable failure, rather than proceeding into an unrelated
  cascade of failures that would otherwise look the same as the tool itself failing

#### Scenario: The tool does not retain access to the saved console file descriptors

- **WHEN** a pipeline calls `runDotnetTool(..., stderrFile: "err.log")`
- **THEN** neither the tool's own process nor any child it spawns has access to the file
  descriptors this mechanism uses internally to pass output through to the live console
