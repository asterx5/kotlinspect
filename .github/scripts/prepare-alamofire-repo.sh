#!/usr/bin/env bash
# Fills <dest> with the published form of the Alamofire adapter: swift/KotlinspectAlamofire plus the
# license, with its Kotlinspect dependency pointing at <kotlinspect-git-url> <version> instead of
# this repository's folder. Keeps <dest>/.git. Used by release-ios.yml, and checked by ios.yml.
# Usage: prepare-alamofire-repo.sh <dest> <kotlinspect-git-url> <version>
set -euo pipefail
dest="$1"
url="$2"
version="$3"
repo="$(cd "$(dirname "$0")/../.." && pwd)"

mkdir -p "$dest"
find "$dest" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
cp -R "$repo/swift/KotlinspectAlamofire/." "$dest/"
rm -rf "$dest/.build" "$dest/.swiftpm" "$dest/build"
cp "$repo/LICENSE" "$dest/"

manifest="$dest/Package.swift"
sed -i.bak -E "s#\.package\(path: \"\.\./\.\.\"\), // kotlinspect:dependency#.package(url: \"$url\", exact: \"$version\"), // kotlinspect:dependency#" "$manifest"
rm -f "$manifest.bak"
if ! grep -qF ".package(url: \"$url\", exact: \"$version\"), // kotlinspect:dependency" "$manifest"; then
  echo "::error::The Kotlinspect dependency line in $manifest was not rewritten"
  exit 1
fi
if grep -q 'path: "\.\./\.\."' "$manifest"; then
  echo "::error::$manifest still points at a local path"
  exit 1
fi
