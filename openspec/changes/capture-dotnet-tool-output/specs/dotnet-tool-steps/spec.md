## ADDED Requirements

### Requirement: Tool stdout and stderr can be captured separately, alongside its exit code

The system SHALL provide a `captureOutput` option on `runDotnetTool`/`Dotnet.runTool` that, when
`true`, redirects the tool's stdout and stderr to two separate temporary files, reads each back
after the tool exits, and returns a `[exitCode: <int>, stdout: <string>, stderr: <string>]` Map
instead of the bare status/throw behavior the existing `returnStatus` option gives. Both
captured files SHALL be removed after being read regardless of whether the tool succeeded or
failed. The system makes no requirement on how a given tool splits its own output between the
two streams — it captures and returns whatever each stream already contains. This requirement
applies to unix/macOS agents only.

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
