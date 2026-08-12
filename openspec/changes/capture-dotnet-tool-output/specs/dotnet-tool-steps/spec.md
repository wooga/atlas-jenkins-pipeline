## ADDED Requirements

### Requirement: Tool stdout and stderr can be captured separately, alongside its exit code

The system SHALL provide a `captureOutput` option on `runDotnetTool`/`Dotnet.runTool` that, when
`true`, duplicates the tool's stdout and stderr to two separate temporary files (while still
passing each stream through live to the console), reads each file back after the tool exits, and
returns a `[exitCode: <int>, stdout: <string>, stderr: <string>]` Map instead of the bare
status/throw behavior the existing `returnStatus` option gives. Both captured files SHALL be
removed after being read regardless of whether the tool succeeded or failed. The system makes no
requirement on how a given tool splits its own output between the two streams — it captures and
returns whatever each stream already contains. This requirement applies to unix/macOS agents
only.

#### Scenario: Output still streams live to the console while being captured

- **WHEN** a pipeline calls `runDotnetTool(..., captureOutput: true)` against a tool that prints
  output with a delay between lines
- **THEN** each line appears in the live Jenkins console log as the tool prints it, not all at
  once only after the tool finishes

#### Scenario: Capturing a failing tool's output

- **WHEN** a pipeline calls `runDotnetTool(packageId: "MyTool", toolBinary: "mytool", args: [...], captureOutput: true)`
  and the tool exits non-zero while printing to both stdout and stderr
- **THEN** the call does not throw
- **AND** the returned Map's `exitCode` reflects the tool's actual non-zero exit status
- **AND** the returned Map's `stdout` contains exactly what the tool printed to stdout
- **AND** the returned Map's `stderr` contains exactly what the tool printed to stderr

#### Scenario: Capturing a succeeding tool's output

- **WHEN** a pipeline calls `runDotnetTool(..., captureOutput: true)` and the tool exits zero
- **THEN** the returned Map's `exitCode` is `0`
- **AND** `stdout`/`stderr` each contain whatever the tool printed to that stream, if anything

#### Scenario: A tool that only writes to one stream

- **WHEN** a pipeline calls `runDotnetTool(..., captureOutput: true)` against a tool that prints
  everything to stdout and never writes to stderr
- **THEN** the returned Map's `stdout` contains everything the tool printed
- **AND** the returned Map's `stderr` is an empty string

#### Scenario: Captured output files do not persist in the workspace

- **WHEN** a pipeline calls `runDotnetTool(..., captureOutput: true)`, regardless of whether the
  tool succeeds or fails
- **THEN** the temporary files used to capture the tool's stdout and stderr no longer exist in
  the workspace once the call returns

#### Scenario: captureOutput and returnStatus are mutually exclusive

- **WHEN** a pipeline calls `runDotnetTool(..., captureOutput: true, returnStatus: true)`
- **THEN** the call fails immediately with a clear error, rather than silently preferring one
  option over the other

#### Scenario: captureOutput is unsupported on Windows

- **WHEN** a pipeline calls `runDotnetTool(..., captureOutput: true)` on a Windows agent
- **THEN** the call fails immediately with a clear error, rather than silently returning the
  plain exit status/throw behavior a Windows caller would otherwise get

#### Scenario: A script exit before the tool's command ever ran does not throw

- **WHEN** a pipeline calls `runDotnetTool(..., captureOutput: true)` and the script exits before
  the tool's command runs (e.g. a missing `DOTNET_CLI_HOME`, or any other precondition failure),
  so neither capture file is ever created
- **THEN** the call does not throw
- **AND** the returned Map's `stdout` and `stderr` are both empty strings
- **AND** the returned Map's `exitCode` reflects the script's actual non-zero exit status

#### Scenario: A cleanup failure does not mask a real failure from the capturing call itself

- **WHEN** a pipeline calls `runDotnetTool(..., captureOutput: true)` and the capturing call
  itself fails for an unrelated reason (e.g. an agent disconnect), and the subsequent cleanup of
  the capture files also fails
- **THEN** the original failure propagates to the caller
- **AND** the cleanup failure is not what the caller sees

#### Scenario: Capture filenames are sanitized when derived from the current stage name

- **WHEN** a pipeline calls `runDotnetTool(..., captureOutput: true)` from within a stage whose
  name contains characters unsafe for a shell-embedded path (e.g. `/`, `$`, a backtick)
- **THEN** the capture mechanism still works
- **AND** the unsafe characters are replaced rather than passed through literally into the
  generated script

#### Scenario: Capture filenames are sanitized when derived from toolBinary

- **WHEN** a pipeline calls `runDotnetTool(toolBinary: <value containing a character unsafe for a
  shell-embedded path>, ..., captureOutput: true)`
- **THEN** the capture mechanism still works
- **AND** the unsafe characters are replaced in the filenames used to capture output, without
  affecting the literal `toolBinary` value used to invoke the tool itself

#### Scenario: A hung/lingering child of the tool does not block the call forever

- **WHEN** a pipeline calls `runDotnetTool(..., captureOutput: true)` against a tool that exits
  but leaves a child process running that still holds the inherited stdout/stderr open
- **THEN** the call still returns, rather than blocking indefinitely
- **AND** the returned `stdout`/`stderr` contain whatever was captured before the wait was given
  up on, which may be truncated relative to what the lingering child eventually would have
  produced

#### Scenario: A stale artifact from a previous run at the same capture path does not silently break capture

- **WHEN** a pipeline calls `runDotnetTool(..., captureOutput: true)` and a file already exists
  at the path this call would use to set up its capture mechanism, left behind by a previous
  crashed or killed run
- **THEN** the stale artifact does not cause this call to silently capture nothing while still
  reporting an unremarkable exit code

#### Scenario: A failure setting up the capture mechanism itself is reported distinguishably

- **WHEN** a pipeline calls `runDotnetTool(..., captureOutput: true)` and setting up the capture
  mechanism fails for a reason unrelated to the tool itself (e.g. the agent's disk is full or
  permissions prevent creating a file at the capture path)
- **THEN** the call reports a distinguishable failure, rather than proceeding into an unrelated
  cascade of failures that would otherwise look the same as the tool itself failing

#### Scenario: The tool does not retain access to the saved console file descriptors

- **WHEN** a pipeline calls `runDotnetTool(..., captureOutput: true)`
- **THEN** neither the tool's own process nor any child it spawns has access to the file
  descriptors this mechanism uses internally to pass output through to the live console
