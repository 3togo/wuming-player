#!/bin/bash
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd -P)"

# Build steps (prepareNativePython*, etc.) invoke `python3 -m pip`; the system
# interpreter has no pip, so prefer the project venv when present.
if [[ -f "$HOME/venv/bin/activate" ]]; then
  # shellcheck disable=SC1091
  source "$HOME/venv/bin/activate"
fi

TENCENT_MIRROR="https://mirrors.cloud.tencent.com/AndroidSDK"
USTC_MIRROR="https://mirrors.ustc.edu.cn/android/repository"
TUNA_MIRROR="https://mirrors.tuna.tsinghua.edu.cn/android/repository"
GOOGLE_REPO="https://dl.google.com/android/repository"

SDK_MIRRORS=("$TENCENT_MIRROR" "$USTC_MIRROR" "$TUNA_MIRROR" "$GOOGLE_REPO")

network_reachable() {
  local tmpfile
  tmpfile="$(mktemp)"
  aria2c --max-tries=1 --timeout=5 --connect-timeout=3 --console-log-level=error --summary-interval=0 --allow-overwrite=true --auto-file-renaming=false -d "$(dirname "$tmpfile")" -o "$(basename "$tmpfile")" "https://mirrors.cloud.tencent.com/AndroidSDK/repository2-3.xml" >/dev/null 2>&1
  local rc=$?
  rm -f "$tmpfile"
  [[ $rc -eq 0 ]] && return 0

  aria2c --max-tries=1 --timeout=5 --connect-timeout=3 --console-log-level=error --summary-interval=0 --allow-overwrite=true --auto-file-renaming=false -d "$(dirname "$tmpfile")" -o "$(basename "$tmpfile")" "https://dl.google.com/android/repository/repository2-3.xml" >/dev/null 2>&1
  rc=$?
  rm -f "$tmpfile"
  [[ $rc -eq 0 ]] && return 0

  return 1
}

download_file() {
  local dest="$1"; shift
  local urls=("$@")
  local tmpfile
  tmpfile="$(mktemp)"

  if command -v aria2c >/dev/null 2>&1; then
    local aria_args=(
      --max-connection-per-server=5
      --split=5
      --min-split-size=1M
      --console-log-level=error
      --summary-interval=0
      --download-result=hide
      --allow-overwrite=true
      --auto-file-renaming=false
      --max-tries=1
      --retry-wait=1
      --timeout=15
      --connect-timeout=5
      -d "$(dirname "$tmpfile")"
      -o "$(basename "$tmpfile")"
    )
    for u in "${urls[@]}"; do aria_args+=("$u"); done
    if aria2c "${aria_args[@]}" >/dev/null 2>&1; then
      mv "$tmpfile" "$dest"
      return 0
    fi
    rm -f "$tmpfile"
  fi

  for u in "${urls[@]}"; do
    if curl -fSL --max-time 30 --connect-timeout 5 -o "$tmpfile" "$u" 2>/dev/null; then
      mv "$tmpfile" "$dest"
      return 0
    fi
  done
  rm -f "$tmpfile"
  return 1
}

ensure_java() {
  if command -v java >/dev/null 2>&1; then
    local ver
    ver="$(java -version 2>&1 | head -1 | sed -n 's/.*"\([0-9]*\)\..*/\1/p')"
    if [[ "$ver" -ge 17 ]] 2>/dev/null; then
      export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
      return 0
    fi
    echo "WARNING: detected Java $ver, but the project requires Java 17+." >&2
  fi

  echo "Java 17+ not found. Installing openjdk-17-jdk-headless..." >&2
  if ! sudo apt-get update -qq 2>/dev/null; then
    echo "ERROR: apt-get update failed. Install OpenJDK 17 manually." >&2
    exit 1
  fi
  if ! sudo apt-get install -y openjdk-17-jdk-headless 2>&1; then
    echo "ERROR: openjdk-17-jdk-headless installation failed." >&2
    exit 1
  fi

  local java_bin
  java_bin="$(readlink -f "$(command -v java)")"
  export JAVA_HOME="$(dirname "$(dirname "$java_bin")")"
  echo "Installed Java 17 at $JAVA_HOME" >&2
}

detect_compile_sdk() {
  local gradle_file="$PROJECT_DIR/app/build.gradle"
  local sdk_ver=""
  if [[ -f "$gradle_file" ]]; then
    sdk_ver="$(grep -oP 'compileSdk\s+\K[0-9]+' "$gradle_file" | head -1)"
  fi
  echo "${sdk_ver:-34}"
}

resolve_platform_filename() {
  local sdk_ver="$1"
  local tmpxml
  tmpxml="$(mktemp)"

  for mirror in "${SDK_MIRRORS[@]}"; do
    if download_file "$tmpxml" "${mirror}/repository2-3.xml"; then
      local filename
      filename="$(grep -oP "<url>\K[^<]*platform-${sdk_ver}[._]r[0-9]+\.zip" "$tmpxml" 2>/dev/null | sort -V | tail -1)"
      rm -f "$tmpxml"
      if [[ -n "$filename" ]]; then
        echo "$filename"
        return 0
      fi
    fi
  done
  rm -f "$tmpxml"
  return 1
}

download_sdk_package() {
  local pkg_type="$1" sdk_ver="$2" dest_dir="$3"
  local filename="" tmpfile=""
  local urls=()

  case "$pkg_type" in
    platform)
      filename="$(resolve_platform_filename "$sdk_ver")"
      if [[ -z "$filename" ]]; then
        return 1
      fi
      tmpfile="$(mktemp)"
      for mirror in "${SDK_MIRRORS[@]}"; do urls+=("${mirror}/${filename}"); done
      if download_file "$tmpfile" "${urls[@]}"; then
        unzip -q "$tmpfile" -d "$dest_dir" 2>&1
        rm -f "$tmpfile"
        return 0
      fi
      rm -f "$tmpfile"
      ;;
    build-tools)
      for rev in 0 1 2 3; do
        filename="build-tools_r${rev}-linux.zip"
        tmpfile="$(mktemp)"
        urls=()
        for mirror in "${SDK_MIRRORS[@]}"; do urls+=("${mirror}/${filename}"); done
        if download_file "$tmpfile" "${urls[@]}"; then
          unzip -q "$tmpfile" -d "$dest_dir" 2>&1
          rm -f "$tmpfile"
          return 0
        fi
        rm -f "$tmpfile"
      done
      ;;
    platform-tools)
      filename="platform-tools-latest-linux.zip"
      tmpfile="$(mktemp)"
      urls=()
      for mirror in "${SDK_MIRRORS[@]}"; do urls+=("${mirror}/${filename}"); done
      if download_file "$tmpfile" "${urls[@]}"; then
        unzip -q "$tmpfile" -d "$dest_dir" 2>&1
        rm -f "$tmpfile"
        return 0
      fi
      rm -f "$tmpfile"
      ;;
    cmdline-tools)
      filename="commandlinetools-linux-11076708_latest.zip"
      tmpfile="$(mktemp)"
      urls=()
      for mirror in "${SDK_MIRRORS[@]}"; do urls+=("${mirror}/${filename}"); done
      if download_file "$tmpfile" "${urls[@]}"; then
        unzip -q "$tmpfile" -d "$dest_dir" 2>&1
        rm -f "$tmpfile"
        return 0
      fi
      rm -f "$tmpfile"
      ;;
  esac
  return 1
}

ensure_android_sdk() {
  local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/sdk}}"

  export ANDROID_HOME="$sdk_root"
  export ANDROID_SDK_ROOT="$sdk_root"

  local compile_sdk
  compile_sdk="$(detect_compile_sdk)"

  local need_install=()
  [[ -d "$sdk_root/platform-tools" ]] || need_install+=("platform-tools")
  [[ -d "$sdk_root/build-tools/34.0.0" ]] || need_install+=("build-tools;34.0.0")
  [[ -d "$sdk_root/platforms/android-${compile_sdk}.0" || -d "$sdk_root/platforms/android-${compile_sdk}" ]] || need_install+=("platforms;android-${compile_sdk}")
  [[ -d "$sdk_root/platforms/android-34" ]] || need_install+=("platforms;android-34")

  if (( ${#need_install[@]} == 0 )); then
    echo "Android SDK ready at $sdk_root (compileSdk=$compile_sdk)" >&2
    return 0
  fi

  if ! network_reachable; then
    echo "ERROR: Network unreachable. Cannot install missing SDK packages." >&2
    echo "       Missing: ${need_install[*]}" >&2
    echo "       The project requires compileSdk $compile_sdk." >&2
    echo "       Install manually or run on a machine with network access." >&2
    exit 1
  fi

  echo "Installing missing SDK packages: ${need_install[*]}" >&2

  local sdkmanager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"

  if [[ ! -x "$sdkmanager" ]]; then
    echo "Android SDK command-line tools not found. Installing..." >&2

    if ! command -v unzip >/dev/null 2>&1; then
      sudo apt-get install -y unzip 2>&1 || true
    fi

    mkdir -p "$sdk_root/cmdline-tools"
    local tmpdir
    tmpdir="$(mktemp -d)"

    if ! download_sdk_package cmdline-tools "" "$tmpdir"; then
      echo "ERROR: failed to download Android command-line tools." >&2
      rm -rf "$tmpdir"
      exit 1
    fi

    if [[ -d "$tmpdir/cmdline-tools" ]]; then
      mv "$tmpdir/cmdline-tools" "$sdk_root/cmdline-tools/latest"
    else
      echo "ERROR: unexpected zip layout for command-line tools." >&2
      rm -rf "$tmpdir"
      exit 1
    fi
    rm -rf "$tmpdir"
    sdkmanager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"
    chmod +x "$sdkmanager" 2>/dev/null || true
  fi

  # Configure sdkmanager to use Chinese mirrors
  mkdir -p "$HOME/.android"
  local repo_cfg="$HOME/.android/repositories.cfg"
  if ! grep -q "mirrors" "$repo_cfg" 2>/dev/null; then
    cat >> "$repo_cfg" <<'CFG'
### User Sources for Android SDK Manager
https://mirrors.cloud.tencent.com/AndroidSDK/
https://mirrors.ustc.edu.cn/android/repository/
https://mirrors.tuna.tsinghua.edu.cn/android/repository/
CFG
  fi

  if ! yes | "$sdkmanager" --sdk_root="$sdk_root" "${need_install[@]}" 2>&1; then
    echo "sdkmanager failed. Trying direct download..." >&2

    local download_failed=0
    for pkg in "${need_install[@]}"; do
      case "$pkg" in
        "platforms;android-"*)
          local ver="${pkg#platforms;android-}"
          if [[ ! -d "$sdk_root/platforms/android-${ver}.0" && ! -d "$sdk_root/platforms/android-${ver}" ]]; then
            if download_sdk_package platform "$ver" "$sdk_root/platforms"; then
              echo "Installed platforms;android-$ver." >&2
            else
              echo "ERROR: failed to download platforms;android-$ver." >&2
              download_failed=1
            fi
          fi
          ;;
        "build-tools;"*)
          local ver="${pkg#build-tools;}"
          if [[ ! -d "$sdk_root/build-tools/$ver" ]]; then
            if download_sdk_package build-tools "$ver" "$sdk_root/build-tools"; then
              echo "Installed build-tools;$ver." >&2
            else
              echo "ERROR: failed to download build-tools;$ver." >&2
              download_failed=1
            fi
          fi
          ;;
        "platform-tools")
          if [[ ! -d "$sdk_root/platform-tools" ]]; then
            if download_sdk_package platform-tools "" "$sdk_root"; then
              echo "Installed platform-tools." >&2
            else
              echo "ERROR: failed to download platform-tools." >&2
              download_failed=1
            fi
          fi
          ;;
      esac
    done

    if (( download_failed )); then
      echo "" >&2
      echo "ERROR: some SDK packages could not be installed." >&2
      echo "       The project requires compileSdk $compile_sdk." >&2
      echo "       Manual steps:" >&2
      echo "         1. aria2c -x5 ${TENCENT_MIRROR}/repository2-3.xml" >&2
      echo "         2. Find platform-${compile_sdk}*_r*.zip in the XML" >&2
      echo "         3. aria2c -x5 ${TENCENT_MIRROR}/<filename>" >&2
      echo "         4. unzip <filename> -d $sdk_root/platforms/" >&2
      echo "       Mirrors: Tencent / USTC / Tuna / Google" >&2
      exit 1
    fi
  fi

  echo "Android SDK ready at $sdk_root (compileSdk=$compile_sdk)" >&2
}

ensure_java
ensure_android_sdk

launcher="$HOME/.bin/apk-build"
if [[ ! -x "$launcher" ]]; then
  echo "ERROR: $launcher is missing or not executable." >&2
  exit 1
fi
exec "$launcher" "$@"
