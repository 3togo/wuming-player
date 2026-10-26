#!/usr/bin/env bash

set -Eeuo pipefail

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
gradle_wrapper="${project_root}/gradlew"
desktop_launcher="${project_root}/desktop/build/install/tvbox-desktop/bin/tvbox-desktop"
desktop_distribution="${project_root}/desktop/build/install/tvbox-desktop"

install_desktop_java() {
    if ! command -v apt-get >/dev/null 2>&1; then
        echo "Error: a full Java 17 desktop runtime is required, but apt-get is unavailable." >&2
        echo "Install Java 17 with AWT/Swing support using this system's package manager." >&2
        exit 1
    fi

    echo "A full Java 17 desktop runtime is missing." >&2
    echo "Starting installation of the required package: openjdk-17-jre" >&2
    if (( EUID == 0 )); then
        apt-get install openjdk-17-jre
    else
        if ! command -v sudo >/dev/null 2>&1; then
            echo "Error: sudo is unavailable. Run as root or install openjdk-17-jre manually." >&2
            exit 1
        fi
        sudo apt-get install openjdk-17-jre
    fi
}

install_secret_service_tool() {
    if ! command -v apt-get >/dev/null 2>&1; then
        echo "Error: secret-tool is required to store cloud login state in the current user's keyring." >&2
        echo "Install libsecret-tools using this system's package manager." >&2
        exit 1
    fi

    echo "Persistent cloud login support is missing." >&2
    echo "Starting installation of the required package: libsecret-tools" >&2
    if (( EUID == 0 )); then
        apt-get install libsecret-tools
    else
        if ! command -v sudo >/dev/null 2>&1; then
            echo "Error: sudo is unavailable. Run as root or install libsecret-tools manually." >&2
            exit 1
        fi
        sudo apt-get install libsecret-tools
    fi
}

install_mpv() {
    if ! command -v apt-get >/dev/null 2>&1; then
        echo "Error: mpv is required for smooth managed desktop playback, but apt-get is unavailable." >&2
        echo "Install mpv using this system's package manager and run this launcher again." >&2
        exit 1
    fi

    echo "The preferred desktop player, mpv, is missing." >&2
    echo "Starting installation of the required package: mpv" >&2
    if (( EUID == 0 )); then
        apt-get install -y mpv
    else
        if ! command -v sudo >/dev/null 2>&1; then
            echo "Error: sudo is unavailable. Run as root or install mpv manually." >&2
            exit 1
        fi
        sudo apt-get install -y mpv
    fi
}

resolve_java() {
    if [[ -n "${JAVA_HOME:-}" ]]; then
        java_command="${JAVA_HOME}/bin/java"
    else
        java_command="$(command -v java || true)"
    fi

    java_home=""
    if [[ -n "${java_command}" && -x "${java_command}" ]]; then
        java_home="$("${java_command}" -XshowSettings:properties -version 2>&1 \
            | sed -n 's/^[[:space:]]*java\.home = //p' \
            | head -n 1)"
    fi
}

if [[ ! -x "${gradle_wrapper}" ]]; then
    echo "Error: Gradle wrapper is not executable: ${gradle_wrapper}" >&2
    exit 1
fi

needs_display=true
for argument in "$@"; do
    case "${argument}" in
        --help|--smoke|--lan-relay|--log-directory|--inspect-config|--test-js-source|--test-live-source|\
        --test-jvm-spider|--fingerprint-spider-artifact|--probe-guarded-spider|\
        --prepare-guarded-spider|--test-guarded-spider|--diagnose-guarded-spider|\
        --test-cloud-login)
            needs_display=false
            ;;
    esac
done

if [[ "${needs_display}" == true && -z "${DISPLAY:-}" && -z "${WAYLAND_DISPLAY:-}" ]]; then
    echo "Error: no graphical session was detected." >&2
    echo "Run this script from a Linux desktop terminal, or use: $0 --smoke" >&2
    exit 1
fi

if [[ "${needs_display}" == true ]]; then
    resolve_java
    if [[ -z "${java_home}" || ! -f "${java_home}/lib/libawt_xawt.so" ]]; then
        install_desktop_java
        resolve_java
        if [[ -z "${java_home}" || ! -f "${java_home}/lib/libawt_xawt.so" ]]; then
            echo "Error: Java AWT/Swing is still unavailable after package installation." >&2
            echo "Selected Java: ${java_command:-not found}" >&2
            echo "Check JAVA_HOME or select the newly installed Java 17 runtime." >&2
            exit 1
        fi
    fi
    if ! command -v secret-tool >/dev/null 2>&1; then
        install_secret_service_tool
        if ! command -v secret-tool >/dev/null 2>&1; then
            echo "Error: secret-tool is still unavailable after package installation." >&2
            exit 1
        fi
    fi
    if ! command -v mpv >/dev/null 2>&1; then
        install_mpv
        if ! command -v mpv >/dev/null 2>&1; then
            echo "Error: mpv is still unavailable after package installation." >&2
            echo "Confirm that the mpv executable is available in PATH." >&2
            exit 1
        fi
    fi
fi

cd -- "${project_root}"
"${gradle_wrapper}" --quiet :desktop:installDist

if [[ ! -x "${desktop_launcher}" ]]; then
    echo "Error: desktop launcher was not produced: ${desktop_launcher}" >&2
    exit 1
fi

# Gradle replaces distribution JARs during installDist. A private launch snapshot prevents a
# concurrently rebuilt checkout from corrupting lazy class loading in an already-running GUI.
runtime_parent="${XDG_RUNTIME_DIR:-/tmp}"
runtime_directory="$(mktemp -d "${runtime_parent%/}/tvbox-desktop-runtime.XXXXXX")"
cleanup_runtime() {
    rm -rf -- "${runtime_directory}"
}
trap cleanup_runtime EXIT
cp -a --reflink=auto "${desktop_distribution}/." "${runtime_directory}/"
# Private composition protects build inputs only until this standalone copy is ready.
# The inherited descriptor shares the parent's flock; explicit unlock releases it
# even while the wrapper remains alive to wait for the GUI.
if [[ -n "${WUMING_WORKSPACE_LOCK_FD:-}" ]]; then
    python3 -c 'import fcntl, os; fcntl.flock(int(os.environ["WUMING_WORKSPACE_LOCK_FD"]), fcntl.LOCK_UN)'
    unset WUMING_WORKSPACE_LOCK_FD
fi
printf 'Desktop runtime ready; launching. Logs: %s/tvbox-desktop/logs\n' "${XDG_CACHE_HOME:-${HOME}/.cache}" >&2
"${runtime_directory}/bin/tvbox-desktop" "$@"
