#!/usr/bin/env bash
#
# update-gradle-wrapper.sh — Update gradle-wrapper.properties with:
#   --fastest    : Test mirrors and pick the fastest one
#   --latest     : Query Gradle API for the latest version
#   --version X  : Use a specific version (e.g., --version 9.6.1)
#   --all        : Combine --latest + --fastest
#
# Usage:
#   ./scripts/update-gradle-wrapper.sh              : Interactive mode (select from latest 3 versions)
#   ./scripts/update-gradle-wrapper.sh --fastest    : Test mirrors and pick the fastest one
#   ./scripts/update-gradle-wrapper.sh --latest     : Auto-select the absolute latest version
#   ./scripts/update-gradle-wrapper.sh --version X  : Use a specific version (e.g., --version 9.6.1)
#   ./scripts/update-gradle-wrapper.sh --all        : Combine --latest + --fastest
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── Smart project root detection ──────────────────────────────────────────
# Try current directory first, then fall back to script directory's parent
find_project_root() {
  local candidates=("$PWD" "$(dirname "$SCRIPT_DIR")")

  for dir in "${candidates[@]}"; do
    if [[ -f "$dir/gradle/wrapper/gradle-wrapper.properties" ]]; then
      echo "$dir"
      return 0
    fi
  done

  return 1
}

PROJECT_DIR="$(find_project_root)" || {
  echo "ERROR: Cannot find gradle-wrapper.properties in $PWD or $(dirname "$SCRIPT_DIR")" >&2
  exit 1
}
WRAPPER_PROPS="$PROJECT_DIR/gradle/wrapper/gradle-wrapper.properties"

# ── Mirror URLs (pattern: {VERSION} → gradle-X.Y.Z-bin.zip) ──────────────
MIRRORS=(
  "Official|https://services.gradle.org/distributions/gradle-{VERSION}-bin.zip"
  "Aliyun|https://mirrors.aliyun.com/gradle/distributions/v{VERSION}/gradle-{VERSION}-bin.zip"
  "Tencent|https://mirrors.cloud.tencent.com/gradle/gradle-{VERSION}-bin.zip"
)

# ── Helpers ────────────────────────────────────────────────────────────────
die()   { echo "ERROR: $*" >&2; exit 1; }
info()  { echo "INFO: $*" >&2; }
green() { printf '\033[32m%s\033[0m\n' "$*" >&2; }

usage() {
  cat <<EOF
Usage: $0 [--fastest] [--latest | --version VERSION] [--all]

With no arguments, select one of the latest Gradle releases interactively and
use the fastest available distribution mirror.

Options:
  --version VERSION  Update to a specific Gradle version
  --latest           Update to the latest stable Gradle version
  --fastest          Keep the current version and select the fastest mirror
  --all              Update to the latest version and select the fastest mirror
  -h, --help         Show this help
EOF
}

# ── Parse current version ─────────────────────────────────────────────────
get_current_version() {
  grep '^distributionUrl=' "$WRAPPER_PROPS" 2>/dev/null \
    | sed -E 's|.*gradle-(.+)-bin\.zip.*|\1|' \
    | sed -n '1p'
}

# ── Get current distribution URL ──────────────────────────────────────────
get_current_url() {
  grep '^distributionUrl=' "$WRAPPER_PROPS" 2>/dev/null \
    | sed 's/^distributionUrl=//' \
    | sed 's/\\:/:/g' \
    | sed -n '1p'
}

# ── Fetch latest version from Gradle API ──────────────────────────────────
get_latest_version() {
  local url="https://services.gradle.org/versions/current"
  local json
  json=$(curl -sfL --max-time 15 "$url" 2>/dev/null) || die "Failed to fetch latest version from Gradle API"
  echo "$json" | grep -o '"version" *: *"[^"]*"' | sed -n '1p' | sed 's/.*"version" *: *"\([^"]*\)".*/\1/'
}

# ── Fetch latest N versions from Gradle API ───────────────────────────────
get_latest_versions() {
  local count="${1:-3}"
  local url="https://services.gradle.org/versions/all"
  local json
  json=$(curl -sfL --max-time 30 "$url" 2>/dev/null) || die "Failed to fetch version list from Gradle API"
  # Extract version strings, take top N
  echo "$json" | grep -o '"version" *: *"[^"]*"' | sed -n "1,${count}p" | sed 's/.*"version" *: *"\([^"]*\)".*/\1/'
}

validate_version() {
  local version="$1"
  [[ "$version" =~ ^[0-9]+([.][0-9]+){1,2}(-[0-9A-Za-z][0-9A-Za-z.-]*)?$ ]] \
    || die "Invalid Gradle version: $version"
}

get_distribution_checksum() {
  local version="$1"
  local url="https://services.gradle.org/distributions/gradle-${version}-bin.zip.sha256"
  local checksum

  checksum=$(curl -sfL --max-time 15 "$url" 2>/dev/null) \
    || die "Failed to fetch the checksum for Gradle $version"
  checksum=${checksum%%[[:space:]]*}
  [[ "$checksum" =~ ^[[:xdigit:]]{64}$ ]] \
    || die "Gradle returned an invalid checksum for version $version"
  printf '%s\n' "$checksum"
}

# ── Interactive version selection ─────────────────────────────────────────
select_version_interactive() {
  info "Fetching latest Gradle versions ..."
  local versions
  versions=$(get_latest_versions 3)

  local current_version
  current_version=$(get_current_version)

  echo "" >&2
  info "Available versions (select one):"
  local i=1
  local version_list=()

  while IFS= read -r ver; do
    [[ -z "$ver" ]] && continue
    version_list+=("$ver")
    if [[ "$ver" == "$current_version" ]]; then
      echo -e "\033[32m  [$i] $ver  ← current\033[0m" >&2
    else
      info "  [$i] $ver"
    fi
    i=$((i + 1))
  done <<< "$versions"

  echo "" >&2
  # Read user selection
  local choice
  read -rp "Enter choice [press Enter to exit, 1-$((i-1))]: " choice

  if [[ -z "$choice" ]]; then
    info "Cancelled by user"
    exit 0
  elif [[ "$choice" =~ ^[0-9]+$ ]] && (( choice >= 1 && choice < i )); then
    echo "${version_list[$((choice-1))]}"
  else
    die "Invalid selection: $choice"
  fi
}

# ── Test mirror speed (download first 256 KB, measure time) ───────────────
test_mirror_speed() {
  local label="$1"
  local url="$2"
  local start end elapsed

  start=$(date +%s%N)
  # Download only the first 256 KB to keep it fast
  if curl -sfL --max-time 20 --range 0-262143 "$url" -o /dev/null 2>/dev/null; then
    end=$(date +%s%N)
    elapsed=$(( (end - start) / 1000000 ))  # milliseconds
    echo "$elapsed $label $url"
  else
    echo "999999 $label $url"  # mark as failed
  fi
}

# ── Find fastest mirror ───────────────────────────────────────────────────
find_fastest_mirror() {
  local version="$1"
  local results=()
  local fastest_time=999999
  local fastest_label=""
  local fastest_url=""

  info "Testing mirrors for version $version ..."

  for entry in "${MIRRORS[@]}"; do
    local label="${entry%%|*}"
    local pattern="${entry#*|}"
    local url="${pattern//\{VERSION\}/$version}"

    info "  Testing $label ..."
    local result
    result=$(test_mirror_speed "$label" "$url")
    results+=("$result")

    local time_ms="${result%% *}"
    if (( time_ms < fastest_time )); then
      fastest_time=$time_ms
      fastest_label="${result#* }"
      fastest_label="${fastest_label%% *}"
      fastest_url="${result##* }"
    fi
  done

  local current_url
  current_url=$(get_current_url)

  echo "" >&2
  info "Results (fastest first):"
  printf '%s\n' "${results[@]}" | sort -n | while read -r time label url; do
    if (( time >= 999999 )); then
      info "  FAIL  $label"
    elif [[ "$url" == "$current_url" ]]; then
      green "  ${time}ms  $label  ← current"
    else
      info "  ${time}ms  $label"
    fi
  done

  echo "" >&2
  if (( fastest_time >= 999999 )); then
    die "All mirrors failed"
  fi
  info "Fastest: $fastest_label (${fastest_time}ms)"
  echo "$fastest_url"
}

# ── Update wrapper properties using Python for reliable escaping ──────────
update_wrapper() {
  local url="$1"
  local checksum="$2"

  python3 -c "
import sys

props_file = sys.argv[1]
new_url = sys.argv[2]
new_checksum = sys.argv[3]

# Read existing content
with open(props_file, 'r') as f:
    lines = f.readlines()

# Escape colons for gradle properties format (https:// → https\\://)
escaped_url = new_url.replace(':', '\\\\:')

# Replace the URL and checksum in place, retaining the rest of the wrapper
# configuration and its ordering.
new_lines = []
url_written = False
checksum_written = False
for line in lines:
    stripped = line.strip()
    if stripped.startswith('distributionUrl='):
        new_lines.append('distributionUrl=' + escaped_url + '\n')
        url_written = True
    elif stripped.startswith('distributionSha256Sum='):
        new_lines.append('distributionSha256Sum=' + new_checksum + '\n')
        checksum_written = True
    else:
        new_lines.append(line)

if not url_written:
    raise SystemExit('distributionUrl is missing from ' + props_file)
if not checksum_written:
    url_index = next(i for i, line in enumerate(new_lines)
                     if line.startswith('distributionUrl='))
    new_lines.insert(url_index + 1, 'distributionSha256Sum=' + new_checksum + '\n')

with open(props_file, 'w') as f:
    f.writelines(new_lines)
" "$WRAPPER_PROPS" "$url" "$checksum"

  info "Updated distributionUrl to: $url"
  info "Updated distributionSha256Sum to: $checksum"
}

# ── Main ──────────────────────────────────────────────────────────────────
main() {
  info "Project root: $PROJECT_DIR"

  local mode_fastest=false
  local mode_latest=false
  local mode_version=""
  local interactive=false

  if [[ $# -eq 0 ]]; then
    interactive=true
    mode_fastest=true
  fi

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --fastest) mode_fastest=true; shift ;;
      --latest)  mode_latest=true;  shift ;;
      --version)
        [[ $# -ge 2 ]] || die "--version requires a value"
        mode_version="$2"
        shift 2
        ;;
      --all)     mode_fastest=true; mode_latest=true; shift ;;
      --help|-h) usage; exit 0 ;;
      *)         die "Unknown option: $1" ;;
    esac
  done

  if $mode_latest && [[ -n "$mode_version" ]]; then
    die "--latest and --version cannot be used together"
  fi

  local current_version
  current_version=$(get_current_version)
  info "Current version: $current_version"

  local version="$current_version"

  # Resolve version
  if $interactive; then
    version=$(select_version_interactive)
    info "Selected version: $version"
  elif $mode_latest; then
    info "Fetching latest stable Gradle version ..."
    version=$(get_latest_version)
    info "Latest version: $version"
  elif [[ -n "$mode_version" ]]; then
    version="$mode_version"
    info "Using specified version: $version"
  fi
  validate_version "$version"

  # Resolve URL
  local url
  if $mode_fastest; then
    url=$(find_fastest_mirror "$version")
  else
    # Use official URL for the resolved version
    url="https://services.gradle.org/distributions/gradle-${version}-bin.zip"
    info "Using official URL"
  fi

  local checksum
  checksum=$(get_distribution_checksum "$version")
  update_wrapper "$url" "$checksum"
  info "Done. Run './gradlew --version' to verify."
}

main "$@"
