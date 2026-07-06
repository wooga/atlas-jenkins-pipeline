#!/usr/bin/env bash
# Provision the .NET SDK for CI by wrapping Microsoft's official dotnet-install.sh.
#
# - Downloads the official install script at runtime (no vendored copy).
# - Resolves the SDK to install from, in precedence order: --version (exact),
#   --channel (floating - only used when a caller explicitly asks for it),
#   --global-json / a global.json found in the working directory (its
#   sdk.version's major.minor is installed as a floating channel, tracking
#   the latest patch - matching how GitHub Actions' setup-dotnet resolves a
#   global.json), or --default-version (the org-wide default, exact).
#   The Dotnet.groovy model class is the single source of truth for selector
#   resolution and always passes one of these flags before invoking this
#   script; it is an error for none of them to be given.
# - --install-dir is required: installs into a custom shared cache directory,
#   NOT the tool's own default per-user directory, so it stays a predictable,
#   org-standard location independent of dotnet's own conventions.
# - For an exact selector (--version or --default-version), the cache is
#   checked directly by directory presence - no network call at all on a hit.
#   A floating channel selector (--channel, or derived from global.json) needs
#   a --dry-run round trip to resolve the concrete version before it can check
#   the cache.
# - Always logs the resolved version, the selector that produced it, the
#   install dir, and cache hit/miss.
# - Serializes concurrent installs on the same agent with an atomic lock dir
#   that self-heals after a timeout (a stale lock is broken with a warning).
set -euo pipefail

# Internal-only tuning knob, not part of the caller-facing selector - stays an
# env var since there's no visibility need for it on the Jenkins console log.
LOCK_TIMEOUT_SECONDS="${DOTNET_INSTALL_LOCK_TIMEOUT:-300}"
INSTALL_SCRIPT_URL="https://dot.net/v1/dotnet-install.sh"

log()  { printf '%s [dotnet-install] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
warn() { printf '%s [dotnet-install] WARNING: %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
die()  { printf '%s [dotnet-install] ERROR: %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; exit 1; }

# Epoch seconds of a path's last modification (GNU stat, then BSD/macOS stat).
get_mtime() { stat -c %Y "$1" 2>/dev/null || stat -f %m "$1" 2>/dev/null; }

INSTALL_DIR=""
CLI_VERSION=""
CLI_CHANNEL=""
CLI_GLOBAL_JSON=""
CLI_DEFAULT_VERSION=""

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --install-dir)
        [ $# -ge 2 ] || die "--install-dir requires a value"
        INSTALL_DIR="$2"; shift 2 ;;
      --version)
        [ $# -ge 2 ] || die "--version requires a value"
        CLI_VERSION="$2"; shift 2 ;;
      --channel)
        [ $# -ge 2 ] || die "--channel requires a value"
        CLI_CHANNEL="$2"; shift 2 ;;
      --global-json)
        [ $# -ge 2 ] || die "--global-json requires a value"
        CLI_GLOBAL_JSON="$2"; shift 2 ;;
      --default-version)
        [ $# -ge 2 ] || die "--default-version requires a value"
        CLI_DEFAULT_VERSION="$2"; shift 2 ;;
      *)
        die "Unknown argument: $1" ;;
    esac
  done
  [ -n "$INSTALL_DIR" ] || die "--install-dir is required"
}

# Resolve the selector to install, in precedence order: --version, --channel,
# --global-json / auto-detected global.json (its sdk.version's major.minor
# installed as a floating channel), --default-version. Each source gets a
# distinct, clearly-worded description so the resulting log line always says
# *why* this version was chosen. Prints "<kind>|<value>|<description>". Errors
# if none is set - the caller (Dotnet.groovy) is expected to always pass one.
resolve_selector() {
  if [ -n "$CLI_VERSION" ]; then
    printf 'version|%s|explicit version argument (%s)\n' "$CLI_VERSION" "$CLI_VERSION"
    return
  fi
  if [ -n "$CLI_CHANNEL" ]; then
    printf 'channel|%s|explicit channel argument (%s)\n' "$CLI_CHANNEL" "$CLI_CHANNEL"
    return
  fi

  local global_json="$CLI_GLOBAL_JSON"
  if [ -z "$global_json" ] && [ -f "$PWD/global.json" ]; then
    global_json="$PWD/global.json"
  fi
  if [ -n "$global_json" ]; then
    [ -f "$global_json" ] || die "global.json not found at '$global_json'"
    local version major minor channel
    version="$(grep -oE '"version"[[:space:]]*:[[:space:]]*"[^"]+"' "$global_json" \
      | head -n1 | sed -E 's/.*:[[:space:]]*"([^"]+)".*/\1/')"
    [ -n "$version" ] || die "Unable to read sdk.version from $global_json"
    major="$(printf '%s' "$version" | cut -d. -f1)"
    minor="$(printf '%s' "$version" | cut -d. -f2)"
    { [ -n "$major" ] && [ -n "$minor" ]; } \
      || die "sdk.version '$version' is not in expected major.minor.patch form"
    channel="${major}.${minor}"
    printf 'channel|%s|channel %s from global.json at %s (sdk.version %s)\n' "$channel" "$channel" "$global_json" "$version"
    return
  fi

  if [ -n "$CLI_DEFAULT_VERSION" ]; then
    printf 'version|%s|org-wide default version (no global.json found)\n' "$CLI_DEFAULT_VERSION"
    return
  fi

  die "No selector given: expected one of --version, --channel, --global-json, --default-version, or a global.json in $PWD"
}

OWNS_LOCK=0
SCRIPT_TMP_DIR=""
LOCK_DIR=""
cleanup() {
  if [ "$OWNS_LOCK" = "1" ]; then rm -rf "$LOCK_DIR" 2>/dev/null || true; fi
  if [ -n "$SCRIPT_TMP_DIR" ]; then rm -rf "$SCRIPT_TMP_DIR" 2>/dev/null || true; fi
}
trap cleanup EXIT INT TERM

acquire_lock() {
  local now mtime age
  while ! mkdir "$LOCK_DIR" 2>/dev/null; do
    now="$(date +%s)"
    mtime="$(get_mtime "$LOCK_DIR" || echo "$now")"
    age=$(( now - mtime ))
    if [ "$age" -ge "$LOCK_TIMEOUT_SECONDS" ]; then
      warn "Install lock '$LOCK_DIR' held for ${age}s (>= ${LOCK_TIMEOUT_SECONDS}s); assuming a crashed run left it behind and breaking it."
      rm -rf "$LOCK_DIR" 2>/dev/null || true
      continue
    fi
    sleep 2
  done
  OWNS_LOCK=1
}

download_install_script() {
  local script
  SCRIPT_TMP_DIR="$(mktemp -d)"
  script="$SCRIPT_TMP_DIR/dotnet-install.sh"
  log "Downloading official install script from $INSTALL_SCRIPT_URL"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$INSTALL_SCRIPT_URL" -o "$script" || die "Failed to download $INSTALL_SCRIPT_URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -qO "$script" "$INSTALL_SCRIPT_URL" || die "Failed to download $INSTALL_SCRIPT_URL"
  else
    die "Neither curl nor wget is available to download the install script"
  fi
  chmod +x "$script"
  printf '%s\n' "$script"
}

main() {
  parse_args "$@"
  LOCK_DIR="${INSTALL_DIR}.install.lock"

  local selector kind value description resolved_version script
  local -a selector_args

  selector="$(resolve_selector)"
  kind="$(printf '%s' "$selector" | cut -d'|' -f1)"
  value="$(printf '%s' "$selector" | cut -d'|' -f2)"
  description="$(printf '%s' "$selector" | cut -d'|' -f3-)"

  mkdir -p "$INSTALL_DIR"
  acquire_lock
  log "Acquired install lock: $LOCK_DIR"

  if [ "$kind" = "version" ]; then
    resolved_version="$value"
    selector_args=(--version "$value")
    if [ -d "$INSTALL_DIR/sdk/$resolved_version" ]; then
      log "Using SDK $resolved_version ($description) - cache hit at $INSTALL_DIR"
      return
    fi
  else
    selector_args=(--channel "$value")
  fi

  script="$(download_install_script)"

  if [ "$kind" = "channel" ]; then
    # A floating channel selector doesn't name a concrete version, so resolve
    # it up front via --dry-run to check the cache before attempting install.
    resolved_version="$("$script" --install-dir "$INSTALL_DIR" "${selector_args[@]}" --dry-run 2>&1 \
      | grep -oE '[0-9]+\.[0-9]+\.[0-9]+[A-Za-z0-9.-]*' | tail -n1 || true)"
    [ -n "$resolved_version" ] || die "Could not resolve an exact SDK version from dry-run output for $description"

    if [ -d "$INSTALL_DIR/sdk/$resolved_version" ]; then
      log "Using SDK $resolved_version ($description) - cache hit at $INSTALL_DIR"
      return
    fi
  fi

  log "Installing SDK $resolved_version ($description) into $INSTALL_DIR"
  "$script" --install-dir "$INSTALL_DIR" "${selector_args[@]}" || die "dotnet-install failed for $description"
  log "Using SDK $resolved_version ($description) - freshly installed at $INSTALL_DIR"
}

main "$@"
