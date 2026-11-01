#!/usr/bin/env bash
# Cross-compiles the Rust core for arm64 Android and packages it into the debug APK as libtin.so.
set -euo pipefail
cd "$(dirname "$0")/.."

NDK="${ANDROID_NDK_HOME:-/opt/homebrew/share/android-commandlinetools/ndk/28.2.13676358}"
HOST=$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK/toolchains/llvm/prebuilt/$HOST/bin/aarch64-linux-android35-clang"

cargo build --release --target aarch64-linux-android
mkdir -p android/app/src/main/jniLibs/arm64-v8a
cp target/aarch64-linux-android/release/tin android/app/src/main/jniLibs/arm64-v8a/libtin.so

cd android
./gradlew --quiet assembleDebug
ls -l app/build/outputs/apk/debug/app-debug.apk
