#!/usr/bin/env bash
# Cross-compiles the Rust core for arm64 Android and packages it into the APK as libtin.so.
# BUILD_TYPE=release builds the release APK (signed when tin.signing.* is set); the default is debug.
set -euo pipefail
cd "$(dirname "$0")/.."

NDK="${ANDROID_NDK_HOME:-/opt/homebrew/share/android-commandlinetools/ndk/28.2.13676358}"
HOST=$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK/toolchains/llvm/prebuilt/$HOST/bin/aarch64-linux-android35-clang"

cargo build --release --target aarch64-linux-android
mkdir -p android/app/src/main/jniLibs/arm64-v8a
cp target/aarch64-linux-android/release/tin android/app/src/main/jniLibs/arm64-v8a/libtin.so

cd android
case "${BUILD_TYPE:-debug}" in
  release) ./gradlew --quiet :app:assembleRelease; ls -l app/build/outputs/apk/release/app-release*.apk ;;
  debug) ./gradlew --quiet assembleDebug; ls -l app/build/outputs/apk/debug/app-debug.apk ;;
  *) echo "BUILD_TYPE must be debug or release" >&2; exit 2 ;;
esac
