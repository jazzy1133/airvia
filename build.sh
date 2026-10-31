#!/bin/bash
# Airvia build entry point.
# 1. Resolve + download the Compose dependency graph (first run only).
# 2. Run the manual Compose build (aapt2 -> kotlinc + Compose plugin ->
#    R generation -> d8 -> sign) and copy the APK to ../your_files.
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f libs/resolved-artifacts.txt ]; then
  echo "=== Resolving dependencies (first run) ==="
  python3 scripts/download_deps.py
fi

bash scripts/manual_build.sh

mkdir -p dist
cp build-manual/apk/airvia.apk dist/Airvia.apk
echo "=== Airvia APK ready ==="
ls -lh dist/Airvia.apk
(sha256sum dist/Airvia.apk 2>/dev/null || shasum -a 256 dist/Airvia.apk)
