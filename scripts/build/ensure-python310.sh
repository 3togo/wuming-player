#!/usr/bin/env bash
#
# ensure-python310.sh - Ensure Python 3.10 is available for Chaquopy builds.
#
# Chaquopy requires a buildPython that matches the Python version configured in
# chaquo/build.gradle (currently 3.10). This script checks if a suitable Python
# 3.10 interpreter exists and, if not, installs one via pyenv.
#
# Usage:
#   ./ensure-python310.sh              # interactive (may prompt for pyenv install)
#   ./ensure-python310.sh --ci         # non-interactive, exit 1 on failure
#
# The script updates user config with the correct buildPython path.
#

set -euo pipefail

PYTHON_VERSION="3.10"
PYTHON_FULL_VERSION="3.10.20"
CI_MODE=false
[[ "${1:-}" == "--ci" ]] && CI_MODE=true

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
USER_CONFIG_SCRIPT="$SCRIPT_DIR/scripts/user_config.py"

# --- Check if a working Python 3.10 is already configured ---

check_build_python() {
    local python_path

    python_path=$(python3 "$USER_CONFIG_SCRIPT" get buildPython)

    # If a path is configured, verify it works
    if [[ -n "$python_path" && -x "$python_path" ]]; then
        local version
        version=$("$python_path" --version 2>&1 || true)
        if [[ "$version" == *"3.10"* ]]; then
            info "Found working Python 3.10 at: $python_path ($version)"
            return 0
        else
            warn "Configured buildPython is $version, not 3.10. Will fix."
        fi
    fi

    # Try common locations
    local candidates=(
        "$HOME/.pyenv/versions/${PYTHON_FULL_VERSION}/bin/python${PYTHON_VERSION}"
        "$HOME/.pyenv/versions/${PYTHON_VERSION}/bin/python${PYTHON_VERSION}"
    )

    for candidate in "${candidates[@]}"; do
        if [[ -x "$candidate" ]]; then
            local version
            version=$("$candidate" --version 2>&1 || true)
            if [[ "$version" == *"3.10"* ]]; then
                info "Found Python 3.10 at: $candidate ($version)"
                update_user_config "$candidate"
                return 0
            fi
        fi
    done

    return 1
}

# --- Update user config with the correct buildPython path ---

update_user_config() {
    local python_path="$1"

    python3 "$USER_CONFIG_SCRIPT" set buildPython "$python_path"
    info "Updated user config: buildPython=$python_path"
}

# --- Ensure pyenv is installed ---

ensure_pyenv() {
    if command -v pyenv &>/dev/null; then
        info "pyenv is already installed ($(pyenv --version 2>&1))"
        return 0
    fi

    warn "pyenv is not installed."

    if $CI_MODE; then
        error "pyenv is required but not installed. Install it first: https://pyenv.run"
        return 1
    fi

    echo ""
    echo "  This project requires Python 3.10 for Chaquopy builds."
    echo "  The recommended way to install it is via pyenv."
    echo ""
    read -rp "  Install pyenv now? [y/N] " answer
    if [[ "${answer,,}" != "y" ]]; then
        error "Cannot continue without pyenv. Install it manually: https://pyenv.run"
        return 1
    fi

    info "Installing pyenv..."
    curl -fsSL https://pyenv.run | bash

    # Source pyenv into the current shell
    export PYENV_ROOT="$HOME/.pyenv"
    export PATH="$PYENV_ROOT/bin:$PATH"
    eval "$(pyenv init -)"

    if ! command -v pyenv &>/dev/null; then
        error "pyenv installation failed. Please install manually: https://pyenv.run"
        return 1
    fi

    # Remind user to update their shell config
    warn "pyenv installed. Add these lines to your ~/.bashrc or ~/.zshrc:"
    echo ""
    echo '  export PYENV_ROOT="$HOME/.pyenv"'
    echo '  [[ -d $PYENV_ROOT/bin ]] && export PATH="$PYENV_ROOT/bin:$PATH"'
    echo '  eval "$(pyenv init -)"'
    echo ""
    info "Continuing with Python 3.10 installation..."
}

# --- Install Python 3.10 via pyenv ---

install_python310() {
    info "Installing Python ${PYTHON_FULL_VERSION} via pyenv..."
    info "This may take a few minutes on first run (compiling from source)."

    # Install build dependencies reminder
    if ! $CI_MODE; then
        warn "If the build fails, you may need to install build dependencies first:"
        echo "  See: https://github.com/pyenv/pyenv/wiki#suggested-build-environment"
        echo ""
    fi

    pyenv install "$PYTHON_FULL_VERSION" 2>&1 || {
        # Try just "3.10" as a fallback if the exact version isn't available
        info "Trying Python ${PYTHON_VERSION} (latest patch)..."
        pyenv install "$PYTHON_VERSION" 2>&1 || {
            error "Failed to install Python ${PYTHON_VERSION} via pyenv."
            error "Make sure you have the required build dependencies:"
            error "  https://github.com/pyenv/pyenv/wiki#suggested-build-environment"
            return 1
        }
    }
}

# --- Find the installed Python 3.10 binary ---

find_python310() {
    local candidates=(
        "$HOME/.pyenv/versions/${PYTHON_FULL_VERSION}/bin/python${PYTHON_VERSION}"
    )

    # Also check for whatever pyenv actually installed
    local pyenv_dir="$HOME/.pyenv/versions"
    if [[ -d "$pyenv_dir" ]]; then
        for dir in "$pyenv_dir"/3.10.*; do
            if [[ -d "$dir" ]]; then
                candidates+=("$dir/bin/python${PYTHON_VERSION}")
            fi
        done
    fi

    for candidate in "${candidates[@]}"; do
        if [[ -x "$candidate" ]]; then
            echo "$candidate"
            return 0
        fi
    done

    return 1
}

# --- Main ---

main() {
    echo ""
    echo "=== Python 3.10 Check for Chaquopy ==="
    echo ""

    # Step 1: Check if Python 3.10 is already available
    if check_build_python; then
        echo ""
        info "All good! Python 3.10 is ready for Chaquopy builds."
        return 0
    fi

    # Step 2: Ensure pyenv is available
    ensure_pyenv || return 1

    # Step 3: Check if pyenv already has Python 3.10
    local found
    if found=$(find_python310); then
        info "Python 3.10 already installed via pyenv at: $found"
        update_user_config "$found"
        echo ""
        info "All good! Python 3.10 is ready for Chaquopy builds."
        return 0
    fi

    # Step 4: Install Python 3.10 via pyenv
    install_python310 || return 1

    # Step 5: Find and configure the installed binary
    if found=$(find_python310); then
        update_user_config "$found"
        echo ""
        info "Python 3.10 installed and configured successfully!"
        info "  Path: $found"
        info "  Version: $($found --version 2>&1)"
    else
        error "Python 3.10 was installed but the binary could not be found."
        error "Please check your pyenv installation."
        return 1
    fi
}

main
