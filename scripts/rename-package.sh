#!/usr/bin/env bash
#
# rename-package.sh — Rename the Java/Kotlin package root across an Android
# multi-module project, including isolated modules and pre-built AARs.
#
# This script renames a package-root segment (e.g. "fongmi" → "wuming") in:
#   - All .kt / .java / .xml source files (package declarations + imports)
#   - All .gradle / .kts build files (namespace, group, verification regex,
#     slash-separated path strings)
#   - ProGuard / R8 keep rules (.pro)
#   - Version catalog (.toml) and .properties files
#   - JSON config files
#   - Directory layout (com/<old>/ → com/<new>/)
#   - Isolated vendor-snapshot modules (source + namespace)
#   - Pre-built AAR artifacts in app/libs/ (rebuilt from source)
#   - SHA-256 verification hashes in build.gradle
#
# It deliberately does NOT touch:
#   - Git history or build/ output directories
#   - IDE cache directories (.idea/, artifacts/)
#
# Usage:
#   ./scripts/rename-package.sh <old-segment> <new-segment> [--keep-app-id]
#
# Options:
#   --keep-app-id   Preserve applicationId as com.<old>.android.tv instead of
#                   renaming it. Use when installed users must receive updates
#                   through the same Play Store / sideload package identity.
#
# Example (full rename):
#   ./scripts/rename-package.sh fongmi wuming
#
# Example (keep applicationId for update compatibility):
#   ./scripts/rename-package.sh fongmi wuming --keep-app-id
#
# Exit codes:
#   0  Success
#   1  Invalid arguments
#   2  AAR rebuild failed
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Argument validation
# ---------------------------------------------------------------------------
KEEP_APP_ID=false
if [[ $# -eq 3 && "$3" == "--keep-app-id" ]]; then
    KEEP_APP_ID=true
    shift
elif [[ $# -ne 2 ]]; then
    echo "Usage: $0 <old-segment> <new-segment> [--keep-app-id]"
    echo "Example: $0 fongmi wuming"
    echo "         $0 fongmi wuming --keep-app-id"
    exit 1
fi

OLD="$1"   # e.g. "fongmi"
NEW="$2"   # e.g. "wuming"

# Dot-separated forms used in package declarations and imports
OLD_PKG="com.${OLD}"    # e.g. "com.fongmi"
NEW_PKG="com.${NEW}"    # e.g. "com.wuming"

# Slash-separated forms used in directory paths and build.gradle path strings
OLD_DIR="com/${OLD}"    # e.g. "com/fongmi"
NEW_DIR="com/${NEW}"    # e.g. "com/wuming"

# Project root (directory containing this script's parent)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "=== Package rename: ${OLD_PKG} → ${NEW_PKG} ==="
echo "Project root: $ROOT"
if [[ "$KEEP_APP_ID" == "true" ]]; then
    echo "applicationId: will be PRESERVED as ${OLD_PKG}.android.tv"
else
    echo "applicationId: will be RENAMED to ${NEW_PKG}.android.tv"
fi
echo ""

# ---------------------------------------------------------------------------
# Step 1: Replace package references in all source files
#
# Handles .kt, .java, .xml — the three file types that contain package
# declarations and import statements. Covers all modules including isolated
# vendor-snapshots, desktop, chaquo, quickjs, etc.
# ---------------------------------------------------------------------------
echo "[1/8] Replacing ${OLD_PKG} → ${NEW_PKG} in source files..."

find . -type f \( -name "*.kt" -o -name "*.java" -o -name "*.xml" \) \
    ! -path "*/build/*" ! -path "*/.gradle/*" ! -path "*/.git/*" \
    ! -path "*/bin/*" ! -path "*/.idea/*" \
    -exec sed -i "s/${OLD_PKG}/${NEW_PKG}/g" {} +

echo "    Done."

# ---------------------------------------------------------------------------
# Step 2: Replace in build configuration files
#
# .gradle files contain several forms of package references that each need
# a separate sed pass:
#
#   Pass 1 — Simple dotted references:
#       namespace = 'com.fongmi.android.tv'
#       group = 'com.fongmi.tv'
#       EventBus index arguments
#
#   Pass 2 — Regex-escaped dotted references:
#       line ==~ /\s*import\s+(?:static\s+)?com\.fongmi\.android(?:\..*)?\s*;?\s*/
#   The backslash before the dot is a regex escape, so com\.fongmi in the
#   file is literal text that must be matched differently from com.fongmi.
#
#   Pass 3 — Slash-separated path strings:
#       'app/src/main/java/com/fongmi/android/tv/bean/History'
#       'com/fongmi/android/tv/utils/GridSpecCalculator'
#   These appear in verification tasks and coverage class collectors.
# ---------------------------------------------------------------------------
echo "[2/8] Replacing in build configuration files..."

# Pass 1: Simple dotted references (com.fongmi → com.wuming)
find . -name "*.gradle" ! -path "*/build/*" ! -path "*/.gradle/*" \
    -exec sed -i "s/${OLD_PKG}/${NEW_PKG}/g" {} +

# Pass 2: Regex-escaped references (com\.fongmi → com\.wuming)
# In the file, the text is com\.fongmi (with a literal backslash).
# In sed, we match a literal backslash with \\. so the pattern is com\\.fongmi
find . -name "*.gradle" ! -path "*/build/*" ! -path "*/.gradle/*" \
    -exec sed -i "s/com\\\\.${OLD}/com\\\\.${NEW}/g" {} +

# Pass 3: Slash-separated paths (com/fongmi/ → com/wuming/)
# Use | as sed delimiter to avoid escaping forward slashes
find . -name "*.gradle" ! -path "*/build/*" ! -path "*/.gradle/*" \
    -exec sed -i "s|${OLD_DIR}|${NEW_DIR}|g" {} +

# ProGuard / R8 keep rules
find . -name "*.pro" ! -path "*/build/*" \
    -exec sed -i "s/${OLD_PKG}/${NEW_PKG}/g" {} +

# Version catalog and properties
find . -type f \( -name "*.toml" -o -name "*.properties" \) \
    ! -path "*/build/*" ! -path "*/.gradle/*" \
    -exec sed -i "s/${OLD_PKG}/${NEW_PKG}/g" {} +

# JSON config files (e.g. channels/debug.json)
find . -name "*.json" ! -path "*/build/*" ! -path "*/.gradle/*" ! -path "*/.git/*" \
    ! -path "*/.idea/*" ! -path "*/artifacts/*" \
    -exec sed -i "s/${OLD_PKG}/${NEW_PKG}/g" {} +

echo "    Done."

# ---------------------------------------------------------------------------
# Step 3: Handle applicationId
#
# The applicationId in app/build.gradle determines the Android package
# identity. If it changes, installed users cannot update — they would need
# to uninstall and reinstall. By default this script renames it; pass
# --keep-app-id to preserve the old value for update compatibility.
# ---------------------------------------------------------------------------
echo "[3/8] Handling applicationId..."

APP_GRADLE="app/build.gradle"
if [[ -f "$APP_GRADLE" ]]; then
    if [[ "$KEEP_APP_ID" == "true" ]]; then
        # Step 2 renamed it; restore the original applicationId
        sed -i "s/applicationId \"${NEW_PKG}.android.tv\"/applicationId \"${OLD_PKG}.android.tv\"/" "$APP_GRADLE"
        echo "    applicationId preserved as ${OLD_PKG}.android.tv"
    else
        echo "    applicationId renamed to ${NEW_PKG}.android.tv"
    fi
else
    echo "    WARNING: $APP_GRADLE not found — skipping applicationId"
fi

echo "    Done."

# ---------------------------------------------------------------------------
# Step 4: Move source directories
#
# Rename com/<old>/ to com/<new>/ in every source root. If com/<new>/ already
# exists (partial rename or retry), merge contents into it and remove the old
# directory to avoid duplication.
# ---------------------------------------------------------------------------
echo "[4/8] Moving directories ${OLD_DIR} → ${NEW_DIR}..."

find . -type d -name "${OLD}" -path "*/com/${OLD}" \
    ! -path "*/build/*" ! -path "*/.gradle/*" ! -path "*/bin/*" ! -path "*/.git/*" \
    | while IFS= read -r dir; do
        parent="$(dirname "$dir")"
        target="${parent}/${NEW}"
        if [[ -d "$target" ]]; then
            # Target exists — merge contents (handles retries gracefully)
            cp -r "$dir"/* "$target/" 2>/dev/null || true
            rm -rf "$dir"
        else
            mv "$dir" "$target"
        fi
    done

echo "    Done."

# ---------------------------------------------------------------------------
# Step 5: Rebuild pre-built AAR dependencies
#
# The project may ship pre-built AARs in app/libs/ (e.g. hook-release.aar)
# whose source lives in isolated/vendor-snapshots/. When the package root
# changes, these AARs must be rebuilt — otherwise their internal classes
# still use the old package, causing ClassNotFoundException at runtime.
#
# This step:
#   1. Identifies AARs that contain classes under com/<old>/
#   2. Finds the corresponding isolated source module
#   3. Rebuilds the AAR using a temporary standalone Gradle project
#   4. Copies the new AAR back to app/libs/
#   5. Updates the SHA-256 hash in build.gradle
# ---------------------------------------------------------------------------
echo "[5/8] Rebuilding pre-built AAR dependencies..."

AAR_DIR="app/libs"
ISOLATED_DIR="isolated/vendor-snapshots"

# Read the isolated source mappings from build.gradle to know which AAR
# comes from which source directory.
# Format in build.gradle:
#   hook: [ source: 'isolated/vendor-snapshots/hook', artifact: 'hook-release.aar', sha256: '...' ]

if [[ -d "$AAR_DIR" && -d "$ISOLATED_DIR" ]]; then
    for aar in "$AAR_DIR"/*.aar; do
        [[ -f "$aar" ]] || continue
        aar_name=$(basename "$aar")

        # Check if this AAR contains classes under the old package
        tmpdir="$(mktemp -d)"
        unzip -o "$aar" classes.jar -d "$tmpdir" > /dev/null 2>&1
        if ! jar tf "$tmpdir/classes.jar" 2>/dev/null | grep -q "${OLD_DIR}/"; then
            # AAR doesn't use the old package — skip
            rm -rf "$tmpdir"
            continue
        fi
        rm -rf "$tmpdir"

        # Find the matching isolated source module by artifact name
        # Extract the module key from the artifact name (e.g. hook-release.aar → hook)
        module_key="${aar_name%%-release.aar}"
        module_src="${ISOLATED_DIR}/${module_key}"

        if [[ ! -d "$module_src" ]]; then
            echo "    WARNING: Source for ${aar_name} not found at ${module_src}/"
            echo "    The AAR still uses old package ${OLD_PKG} — manual rebuild needed"
            continue
        fi

        echo "    Rebuilding ${aar_name} from ${module_src}/..."

        # Create a temporary standalone Gradle project to build the AAR,
        # since isolated modules are not included in settings.gradle.
        build_tmp="$(mktemp -d)"
        mkdir -p "${build_tmp}/${module_key}"
        cp -r "${module_src}/"* "${build_tmp}/${module_key}/"

        # Read AGP version from the project's version catalog
        agp_version=$(grep '^agp' gradle/libs.versions.toml 2>/dev/null | head -1 | sed 's/.*= *"\(.*\)"/\1/')
        if [[ -z "$agp_version" ]]; then
            agp_version="9.3.1"  # fallback
        fi

        # Read compileSdk and minSdk from the module's build.gradle
        compile_sdk=$(grep 'compileSdk' "${module_src}/build.gradle" 2>/dev/null | head -1 | sed 's/.*compileSdk *\([0-9]*\).*/\1/')
        min_sdk=$(grep 'minSdk' "${module_src}/build.gradle" 2>/dev/null | head -1 | sed 's/.*minSdk *\([0-9]*\).*/\1/')
        [[ -z "$compile_sdk" ]] && compile_sdk=37
        [[ -z "$min_sdk" ]] && min_sdk=24

        # Generate settings.gradle with plugin management
        cat > "${build_tmp}/settings.gradle" << GRADLE_SETTINGS
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = '${module_key}-build'
include ':${module_key}'
project(':${module_key}').projectDir = file('${module_key}')
GRADLE_SETTINGS

        # Rewrite build.gradle with explicit AGP version (since the temp
        # project doesn't have access to the root version catalog)
        module_namespace=$(grep 'namespace' "${module_src}/build.gradle" 2>/dev/null | head -1 | sed "s/.*namespace *= *['\"]\(.*\)['\"].*/\1/")
        # The namespace was already renamed by Step 2, so it should be com.<new>.*
        [[ -z "$module_namespace" ]] && module_namespace="${NEW_PKG}.${module_key}"

        cat > "${build_tmp}/${module_key}/build.gradle" << GRADLE_BUILD
plugins {
    id 'com.android.library' version '${agp_version}'
}

android {
    namespace = '${module_namespace}'
    compileSdk ${compile_sdk}

    defaultConfig {
        minSdk ${min_sdk}
        targetSdk ${compile_sdk}
    }

    compileOptions {
        coreLibraryDesugaringEnabled = true
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}

dependencies {
    implementation 'androidx.annotation:annotation:1.9.1'
    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs_nio:2.1.5'
}
GRADLE_BUILD

        # Build the AAR using the project's Gradle wrapper
        if "${ROOT}/gradlew" ":${module_key}:assembleRelease" -p "${build_tmp}" \
                --no-configuration-cache > /dev/null 2>&1; then
            # Copy the rebuilt AAR back to app/libs/
            cp "${build_tmp}/${module_key}/build/outputs/aar/${aar_name}" "${AAR_DIR}/${aar_name}"
            echo "    ${aar_name} rebuilt successfully"

            # Update SHA-256 hash in build.gradle
            new_hash=$(sha256sum "${AAR_DIR}/${aar_name}" | cut -d' ' -f1)
            old_hash=$(grep "${aar_name}" build.gradle 2>/dev/null | grep -o '[a-f0-9]\{64\}' || true)
            if [[ -n "$old_hash" ]]; then
                sed -i "s/${old_hash}/${new_hash}/" build.gradle
                echo "    SHA-256 updated: ${old_hash:0:12}... → ${new_hash:0:12}..."
            fi
        else
            echo "    ERROR: Failed to rebuild ${aar_name}"
            echo "    The AAR still uses old package ${OLD_PKG}"
            rm -rf "${build_tmp}"
            exit 2
        fi

        rm -rf "${build_tmp}"
    done
fi

echo "    Done."

# ---------------------------------------------------------------------------
# Step 6: Handle isolated modules not in settings.gradle
#
# Isolated vendor-snapshot modules (hook) are not
# included in the main Gradle build. Their source code and build.gradle were
# already updated by Steps 1-2, and their directories moved by Step 4.
# This step verifies that no isolated module was missed.
# ---------------------------------------------------------------------------
echo "[6/8] Verifying isolated modules..."

if [[ -d "$ISOLATED_DIR" ]]; then
    for mod in "$ISOLATED_DIR"/*/; do
        [[ -d "$mod" ]] || continue
        modname=$(basename "$mod")
        # Check for any remaining old package references
        stale=$(grep -rn "${OLD_PKG}" "$mod" \
            --include="*.java" --include="*.kt" --include="*.gradle" --include="*.xml" \
            2>/dev/null || true)
        if [[ -n "$stale" ]]; then
            echo "    WARNING: ${modname} still has old package references:"
            echo "$stale" | head -5
        else
            echo "    ${modname}: OK"
        fi
    done
fi

echo "    Done."

# ---------------------------------------------------------------------------
# Step 7: Update Room database schema directories
#
# Room generates schema JSON files under app/schemas/<package>.<DatabaseName>/
# When the package changes, old schema directories remain with the old name.
# The next build will create new schema files under the new package directory,
# but old ones should be removed to avoid confusion.
# ---------------------------------------------------------------------------
echo "[7/8] Cleaning stale Room schema directories..."

SCHEMAS_DIR="app/schemas"
if [[ -d "$SCHEMAS_DIR" ]]; then
    # Remove old package-named schema directories
    find "$SCHEMAS_DIR" -maxdepth 1 -type d -name "*${OLD_PKG}*" \
        -exec rm -rf {} \; 2>/dev/null || true
    echo "    Stale schema directories removed (will be regenerated on next build)"
fi

echo "    Done."

# ---------------------------------------------------------------------------
# Step 8: Verify no stale references remain
# ---------------------------------------------------------------------------
echo "[8/8] Verifying no stale ${OLD_PKG} references remain..."

# Search all text files for the old package name
stale=$(grep -rn "${OLD_PKG}" . \
    --include="*.kt" --include="*.java" --include="*.xml" \
    --include="*.gradle" --include="*.kts" --include="*.pro" \
    --include="*.toml" --include="*.properties" --include="*.json" \
    ! -path "*/build/*" ! -path "*/.gradle/*" ! -path "*/.git/*" \
    ! -path "*/.idea/*" ! -path "*/bin/*" ! -path "*/artifacts/*" \
    2>/dev/null || true)

if [[ "$KEEP_APP_ID" == "true" ]]; then
    # applicationId line is expected to keep the old package
    expected="applicationId \"${OLD_PKG}.android.tv\""
    if [[ -n "$stale" ]]; then
        unexpected=$(echo "$stale" | grep -v "$expected" || true)
        if [[ -n "$unexpected" ]]; then
            echo "    WARNING: Unexpected stale references found:"
            echo "$unexpected" | head -20
            echo "    (Review manually before committing)"
        else
            echo "    Only expected applicationId reference remains — OK"
        fi
    else
        echo "    No stale references found."
    fi
else
    if [[ -n "$stale" ]]; then
        echo "    WARNING: Stale references found:"
        echo "$stale" | head -20
        echo "    (Review manually before committing)"
    else
        echo "    No stale references found."
    fi
fi

# Check for leftover directories
leftover_dirs=$(find . -type d -name "${OLD}" -path "*/com/${OLD}" \
    ! -path "*/build/*" ! -path "*/.gradle/*" ! -path "*/.git/*" ! -path "*/bin/*" \
    2>/dev/null || true)
if [[ -n "$leftover_dirs" ]]; then
    echo "    WARNING: Leftover directories found:"
    echo "$leftover_dirs"
fi

echo ""
echo "=== Rename complete ==="
echo ""
echo "Next steps:"
echo "  1. Run: ./gradlew :app:compileMobileArm64_v8aDebugKotlin"
echo "  2. Fix any compilation errors (usually Java↔Kotlin interop issues)"
echo "  3. Run unit tests: ./gradlew test"
echo "  4. Clean build caches: ./gradlew clean"
echo "  5. Commit: git add -A && git commit -m 'Rename ${OLD_PKG} → ${NEW_PKG}'"
echo ""
echo "Notes:"
if [[ "$KEEP_APP_ID" == "true" ]]; then
    echo "  - applicationId is preserved as ${OLD_PKG}.android.tv for update compatibility"
else
    echo "  - applicationId changed to ${NEW_PKG}.android.tv — installed users must reinstall"
fi
echo "  - Pre-built AARs in app/libs/ have been rebuilt with the new package"
echo "  - SHA-256 hashes in build.gradle have been updated to match new AARs"
echo "  - Room schemas will be regenerated on next build under the new package directory"
echo "  - Run 'git clean -fdx' to remove stale bin/ cache directories (optional)"
