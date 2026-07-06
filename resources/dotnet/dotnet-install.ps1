<#
.SYNOPSIS
  Provision the .NET SDK for CI by wrapping Microsoft's official dotnet-install.ps1.

.DESCRIPTION
  - Downloads the official install script at runtime (no vendored copy).
  - Resolves the SDK to install from, in precedence order: -Version (exact),
    -Channel (floating - only used when a caller explicitly asks for it),
    -GlobalJson / a global.json found in the working directory (its
    sdk.version's major.minor is installed as a floating channel, tracking
    the latest patch - matching how GitHub Actions' setup-dotnet resolves a
    global.json), or -DefaultVersion (the org-wide default, exact). The
    Dotnet.groovy model class is the single source of truth for selector
    resolution and always passes one of these parameters before invoking
    this script; it is an error for none of them to be given.
  - -InstallDir is required: installs into a custom shared cache directory,
    NOT the tool's own default per-user directory, so it stays a predictable,
    org-standard location independent of dotnet's own conventions.
  - For an exact selector (-Version or -DefaultVersion), the cache is checked
    directly by directory presence - no network call at all on a hit. A
    floating channel selector (-Channel, or derived from global.json) needs
    a -DryRun round trip to resolve the concrete version before it can check
    the cache.
  - Always logs the resolved version, the selector that produced it, the
    install dir, and cache hit/miss.
  - Serializes concurrent installs on the same agent with an atomic lock dir
    that self-heals after a timeout (a stale lock is broken with a warning).
#>
[CmdletBinding()]
param(
  [string]$InstallDir,
  [string]$Version,
  [string]$Channel,
  [string]$GlobalJson,
  [string]$DefaultVersion
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $InstallDir) { throw "-InstallDir is required" }
$LockDir            = "$InstallDir.install.lock"
# Internal-only tuning knob, not part of the caller-facing selector - stays an
# env var since there's no visibility need for it on the Jenkins console log.
$LockTimeoutSeconds = if ($env:DOTNET_INSTALL_LOCK_TIMEOUT) { [int]$env:DOTNET_INSTALL_LOCK_TIMEOUT } else { 300 }
$InstallScriptUrl   = 'https://dot.net/v1/dotnet-install.ps1'

function Write-Log  { param([string] $Message) Write-Host ("{0} [dotnet-install] {1}" -f (Get-Date -Format 'HH:mm:ss'), $Message) }
function Write-Warn { param([string] $Message) Write-Warning ("[dotnet-install] {0}" -f $Message) }

# Resolve the selector to install, in precedence order: -Version, -Channel,
# -GlobalJson / auto-detected global.json (its sdk.version's major.minor
# installed as a floating channel), -DefaultVersion. Each source gets a
# distinct, clearly-worded description so the resulting log line always says
# *why* this version was chosen. Throws if none is set - the caller
# (Dotnet.groovy) is expected to always provide one.
function Resolve-Selector {
  if ($Version) {
    return [pscustomobject]@{ Kind = 'version'; Value = $Version; Description = "explicit version argument ($Version)" }
  }
  if ($Channel) {
    return [pscustomobject]@{ Kind = 'channel'; Value = $Channel; Description = "explicit channel argument ($Channel)" }
  }

  $resolvedGlobalJson = $GlobalJson
  if (-not $resolvedGlobalJson) {
    $candidate = Join-Path (Get-Location).Path 'global.json'
    if (Test-Path -LiteralPath $candidate) { $resolvedGlobalJson = $candidate }
  }
  if ($resolvedGlobalJson) {
    if (-not (Test-Path -LiteralPath $resolvedGlobalJson)) { throw "global.json not found at '$resolvedGlobalJson'" }
    $json = Get-Content -Raw -LiteralPath $resolvedGlobalJson | ConvertFrom-Json
    $sdkVersion = $json.sdk.version
    if ([string]::IsNullOrWhiteSpace($sdkVersion)) { throw "Unable to read sdk.version from $resolvedGlobalJson" }
    $parts = $sdkVersion.Split('.')
    if ($parts.Length -lt 2) { throw "sdk.version '$sdkVersion' is not in expected major.minor.patch form" }
    $derivedChannel = '{0}.{1}' -f $parts[0], $parts[1]
    return [pscustomobject]@{ Kind = 'channel'; Value = $derivedChannel; Description = "channel $derivedChannel from global.json at $resolvedGlobalJson (sdk.version $sdkVersion)" }
  }

  if ($DefaultVersion) {
    return [pscustomobject]@{ Kind = 'version'; Value = $DefaultVersion; Description = 'org-wide default version (no global.json found)' }
  }

  throw "No selector given: expected one of -Version, -Channel, -GlobalJson, -DefaultVersion, or a global.json in $((Get-Location).Path)"
}

function Enter-InstallLock {
  while ($true) {
    try {
      New-Item -ItemType Directory -Path $LockDir -ErrorAction Stop | Out-Null
      return
    }
    catch {
      if (-not (Test-Path -LiteralPath $LockDir)) { continue }
      $age = (New-TimeSpan -Start (Get-Item -LiteralPath $LockDir).LastWriteTime -End (Get-Date)).TotalSeconds
      if ($age -ge $LockTimeoutSeconds) {
        Write-Warn ("Install lock '{0}' held for {1}s (>= {2}s); assuming a crashed run left it behind and breaking it." -f $LockDir, [int]$age, $LockTimeoutSeconds)
        Remove-Item -Recurse -Force -LiteralPath $LockDir -ErrorAction SilentlyContinue
        continue
      }
      Start-Sleep -Seconds 2
    }
  }
}

function Get-InstallScript {
  $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ([System.IO.Path]::GetRandomFileName())
  New-Item -ItemType Directory -Path $tmp | Out-Null
  $script = Join-Path $tmp 'dotnet-install.ps1'

  Write-Log "Downloading official install script from $InstallScriptUrl"
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  Invoke-WebRequest -UseBasicParsing -Uri $InstallScriptUrl -OutFile $script

  return [pscustomobject]@{ Script = $script; TmpDir = $tmp }
}

$selector = Resolve-Selector
# Must be a hashtable, not an array: splatting an array (@array) passes each
# element as a POSITIONAL argument (verified by real execution to silently
# misbind "-Channel"/"10.0" onto the script's Channel/Quality parameters
# positionally instead of by name) - only a hashtable splat (@hashtable) maps
# keys to named parameters.
$selectorArgs = if ($selector.Kind -eq 'version') { @{ Version = $selector.Value } } else { @{ Channel = $selector.Value } }

New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
Enter-InstallLock
Write-Log "Acquired install lock: $LockDir"
try {
  $resolvedVersion = $null
  if ($selector.Kind -eq 'version') {
    $resolvedVersion = $selector.Value
    $sdkPath = Join-Path (Join-Path $InstallDir 'sdk') $resolvedVersion
    if (Test-Path -LiteralPath $sdkPath) {
      Write-Log "Using SDK $resolvedVersion ($($selector.Description)) - cache hit at $InstallDir"
      return
    }
  }

  $downloaded = Get-InstallScript
  try {
    if ($selector.Kind -eq 'channel') {
      # A floating channel selector doesn't name a concrete version, so
      # resolve it up front via -DryRun to check the cache before installing.
      # *>&1 (not 2>&1) is required: the official script logs via Write-Host,
      # which writes to the Information stream, not stdout/stderr - 2>&1 alone
      # silently captures nothing (confirmed by real execution on Windows: the
      # dry-run output was visible in the console but $dryRunOutput was empty).
      $dryRunOutput = (& $downloaded.Script -InstallDir $InstallDir @selectorArgs -DryRun *>&1 | Out-String)
      $versionMatches = [regex]::Matches($dryRunOutput, '\d+\.\d+\.\d+[A-Za-z0-9.-]*')
      if ($versionMatches.Count -eq 0) {
        throw "Could not resolve an exact SDK version from dry-run output for $($selector.Description)"
      }
      $resolvedVersion = $versionMatches[$versionMatches.Count - 1].Value

      $sdkPath = Join-Path (Join-Path $InstallDir 'sdk') $resolvedVersion
      if (Test-Path -LiteralPath $sdkPath) {
        Write-Log "Using SDK $resolvedVersion ($($selector.Description)) - cache hit at $InstallDir"
        return
      }
    }

    Write-Log "Installing SDK $resolvedVersion ($($selector.Description)) into $InstallDir"
    & $downloaded.Script -InstallDir $InstallDir @selectorArgs
    if ((Test-Path 'variable:\LASTEXITCODE') -and ($LASTEXITCODE -ne 0)) {
      throw "dotnet-install failed for $($selector.Description) (exit $LASTEXITCODE)"
    }
    Write-Log "Using SDK $resolvedVersion ($($selector.Description)) - freshly installed at $InstallDir"
  }
  finally {
    Remove-Item -Recurse -Force -LiteralPath $downloaded.TmpDir -ErrorAction SilentlyContinue
  }
}
finally {
  Remove-Item -Recurse -Force -LiteralPath $LockDir -ErrorAction SilentlyContinue
}
