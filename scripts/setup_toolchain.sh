#!/bin/bash
# Installs the build toolchain into ~/airvia-toolchain (override: AIRVIA_TOOLCHAIN):
# JDK 17 (Temurin), Kotlin 2.4.21 compiler + Compose plugin.
# The Android SDK (platform android-36, build-tools 36.0.0) is NOT installed here;
# install it with Android Studio or:  sdkmanager "platforms;android-36" "build-tools;36.0.0"
set -euo pipefail

KV=2.4.21
TC=${AIRVIA_TOOLCHAIN:-$HOME/airvia-toolchain}
mkdir -p "$TC/kplugins/embed"
cd "$TC"

case "$(uname -s)" in Darwin) OS=mac;; Linux) OS=linux;; *) echo "unsupported OS"; exit 1;; esac
case "$(uname -m)" in arm64|aarch64) ARCH=aarch64;; x86_64) ARCH=x64;; *) echo "unsupported arch"; exit 1;; esac

if ! ls -d jdk-17* >/dev/null 2>&1; then
  echo "== JDK 17 =="
  curl -fsSL -o jdk17.tgz "https://api.adoptium.net/v3/binary/latest/17/ga/$OS/$ARCH/jdk/hotspot/normal/eclipse"
  tar xzf jdk17.tgz && rm jdk17.tgz
fi
if [ ! -d kotlinc ]; then
  echo "== Kotlin $KV =="
  curl -fsSL -o kotlin.zip "https://github.com/JetBrains/kotlin/releases/download/v$KV/kotlin-compiler-$KV.zip"
  unzip -q -o kotlin.zip && rm kotlin.zip
fi
M=https://repo1.maven.org/maven2/org/jetbrains/kotlin
[ -f kplugins/embed/kotlin-compiler-embeddable-$KV.jar ] || curl -fsSL -o kplugins/embed/kotlin-compiler-embeddable-$KV.jar "$M/kotlin-compiler-embeddable/$KV/kotlin-compiler-embeddable-$KV.jar"
[ -f kplugins/kotlin-compose-compiler-plugin-embeddable-$KV.jar ] || curl -fsSL -o kplugins/kotlin-compose-compiler-plugin-embeddable-$KV.jar "$M/kotlin-compose-compiler-plugin-embeddable/$KV/kotlin-compose-compiler-plugin-embeddable-$KV.jar"
echo "Toolchain ready in $TC"
