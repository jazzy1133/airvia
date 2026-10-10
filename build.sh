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

cp build-manual/apk/airvia.apk ~/workspace/your_files/Airvia-1.2.4.apk
echo "=== Airvia APK ready ==="
ls -lh ~/workspace/your_files/Airvia-1.2.4.apk
sha256sum ~/workspace/your_files/Airvia-1.2.4.apk
