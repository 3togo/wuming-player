#!/usr/bin/env bash
# Build a local Debian/Ubuntu package from the standalone Gradle distribution.
set -Eeuo pipefail
umask 022

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
distribution="${project_root}/desktop/build/install/tvbox-desktop"
version_file="${project_root}/desktop/build/packaging/version.txt"
output_directory="${project_root}/desktop/build/deb"
package_version=""
maintainer="Local Builder <builder@localhost>"
skip_build=false
gradle_options=()

usage() {
    cat <<'HELP'
Usage: ./build-desktop-deb.sh [options]

Builds tvbox-desktop for this Linux host (amd64 or arm64). No sudo is needed.
Requires the project's Java/Gradle build environment and dpkg-deb (dpkg >= 1.19).

  --version VERSION       Override Debian version (default: desktop Gradle version;
                          -SNAPSHOT becomes ~snapshot-1)
  --output-dir DIRECTORY  Output directory (default: desktop/build/deb)
  --maintainer NAME       Debian Maintainer field, e.g. 'Your Name <you@example.org>'
  --offline               Ask Gradle to use only already cached dependencies
  --skip-build            Package an existing installDist; use --version if its
                          packaging/version.txt has not been generated
  -h, --help              Show this help

Examples:
  ./build-desktop-deb.sh
  ./build-desktop-deb.sh --offline --version 0.1.0-1
  ./build-desktop-deb.sh --skip-build --version 0.1.0-1 --output-dir /tmp/packages
HELP
}

fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
need_value() { [[ $# -ge 2 && -n "$2" && "$2" != --* ]] || fail "$1 requires a value"; }

while (( $# )); do
    case "$1" in
        --version) need_value "$@"; package_version="$2"; shift 2 ;;
        --output-dir) need_value "$@"; output_directory="$2"; shift 2 ;;
        --maintainer) need_value "$@"; maintainer="$2"; shift 2 ;;
        --offline) gradle_options+=(--offline); shift ;;
        --skip-build) skip_build=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) fail "Unknown option: $1 (use --help)" ;;
    esac
done

[[ "$(uname -s)" == Linux ]] || fail 'Build this package on Linux.'
for tool in dpkg dpkg-deb install cp find chmod du md5sum sha256sum mktemp; do
    command -v "$tool" >/dev/null 2>&1 || fail "Required build tool is missing: $tool"
done
architecture="$(dpkg --print-architecture)"
case "$architecture" in
    amd64|arm64) ;;
    *) fail "Unsupported architecture: $architecture (build on amd64 or arm64)" ;;
esac
[[ "$maintainer" != *$'\n'* && "$maintainer" != *$'\r'* && "$maintainer" == *'<'*'@'*'>' ]] \
    || fail 'Maintainer must be a single line in Name <email> form.'

validate_version() {
    [[ "$package_version" =~ ^[0-9][0-9A-Za-z.+:~\-]*$ ]] || fail 'Invalid Debian version.'
    dpkg --validate-version "$package_version" || fail "Invalid Debian version: $package_version"
}
[[ -z "$package_version" ]] || validate_version

if [[ "$skip_build" == false ]]; then
    [[ -x "${project_root}/gradlew" ]] || fail 'The Gradle wrapper is not executable.'
    (
        cd -- "$project_root"
        ./gradlew "${gradle_options[@]}" --console=plain :desktop:installDist :desktop:writeDebPackageVersion
    )
fi
[[ -x "${distribution}/bin/tvbox-desktop" && -d "${distribution}/lib" ]] \
    || fail 'Desktop distribution is missing; run without --skip-build.'
if [[ -z "$package_version" ]]; then
    [[ -s "$version_file" ]] || fail 'Version metadata is missing; supply --version or run without --skip-build.'
    package_version="$(<"$version_file")"
    package_version="${package_version/%-SNAPSHOT/~snapshot-1}"
    validate_version
fi

mkdir -p -- "$output_directory"
output_directory="$(cd -- "$output_directory" && pwd -P)"
work_directory="$(mktemp -d "${output_directory}/.tvbox-deb.XXXXXX")"
cleanup() { rm -rf -- "$work_directory"; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
stage="${work_directory}/root"
app_directory="${stage}/usr/lib/tvbox-desktop"
install -d "$stage/DEBIAN" "$app_directory" "$stage/usr/bin" \
    "$stage/usr/share/applications" "$stage/usr/share/icons/hicolor/512x512/apps" \
    "$stage/usr/share/doc/tvbox-desktop"
cp -a -- "${distribution}/." "$app_directory/"
rm -f -- "$app_directory/bin/tvbox-desktop.bat"
# Normalize checkout/build permissions; dpkg-deb supplies root ownership in the archive.
find "$app_directory" -type d -exec chmod 0755 {} +
find "$app_directory" -type f -exec chmod 0644 {} +
chmod 0755 "$app_directory/bin/tvbox-desktop"

cat > "$stage/usr/bin/tvbox-desktop" <<'LAUNCHER'
#!/bin/sh
# All dependencies are installed by APT; launching never invokes Gradle or sudo.
exec /usr/lib/tvbox-desktop/bin/tvbox-desktop "$@"
LAUNCHER
chmod 0755 "$stage/usr/bin/tvbox-desktop"

cat > "$stage/usr/share/applications/tvbox-desktop.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=TVBox Desktop
Comment=Browse video catalogues and watch live TV
Exec=tvbox-desktop
TryExec=tvbox-desktop
Icon=tvbox-desktop
Terminal=false
Categories=AudioVideo;Video;TV;
StartupNotify=true
DESKTOP
install -m 0644 "${project_root}/app/src/main/ic_launcher-playstore.png" \
    "$stage/usr/share/icons/hicolor/512x512/apps/tvbox-desktop.png"
install -m 0644 "${project_root}/LICENSE.md" "$stage/usr/share/doc/tvbox-desktop/copyright"
install -m 0644 "${project_root}/desktop/README.md" "$stage/usr/share/doc/tvbox-desktop/README.md"
if command -v desktop-file-validate >/dev/null 2>&1; then
    desktop-file-validate "$stage/usr/share/applications/tvbox-desktop.desktop"
fi
installed_size="$(du -sk "$stage/usr" | cut -f1)"
cat > "$stage/DEBIAN/control" <<CONTROL
Package: tvbox-desktop
Version: ${package_version}
Section: video
Priority: optional
Architecture: ${architecture}
Maintainer: ${maintainer}
Installed-Size: ${installed_size}
Depends: openjdk-17-jre | java17-runtime, mpv, libsecret-tools, libc6, libstdc++6
Recommends: gnome-keyring
Description: TVBox desktop video catalogue and live TV client
 Standalone Kotlin/JVM desktop application with a Swing interface,
 live playlists, programme guides, catalogue browsing and external playback.
 Uses the system Java runtime and mpv; no Android installation is required.
CONTROL
(
    cd -- "$stage"
    find usr -type f -print0 | LC_ALL=C sort -z | xargs -0 md5sum > DEBIAN/md5sums
)
# An epoch belongs in control metadata, not the conventional archive filename.
package_name="tvbox-desktop_${package_version#*:}_${architecture}.deb"
dpkg-deb --root-owner-group -Zxz -z6 --build "$stage" "$work_directory/$package_name"
mv -f -- "$work_directory/$package_name" "$output_directory/$package_name"
(
    cd -- "$output_directory"
    sha256sum -- "$package_name" > "$package_name.sha256"
)
printf '\nPackage: %s\nChecksum: %s\n' "$output_directory/$package_name" "$output_directory/$package_name.sha256"
printf 'Install: sudo apt install %q\n' "$output_directory/$package_name"
