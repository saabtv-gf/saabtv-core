#!/bin/sh
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SDK_ROOT=$(sed -n 's/^sdk.dir=//p' "$PROJECT_ROOT/local.properties" | head -n 1)
NDK_ROOT="$SDK_ROOT/ndk/27.0.12077973"
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64/bin"
SOURCE="$PROJECT_ROOT/app/src/main/c/mpv_diagnostics.c"
AAR="$PROJECT_ROOT/app/libs/libmpv-saab-namespaced.aar"
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT

unzip -q "$AAR" -d "$TEMP_DIR/aar"

build_abi() {
    abi=$1
    compiler=$2
    output="$PROJECT_ROOT/app/src/main/jniLibs/$abi/libmpvdiag.so"
    mkdir -p "$(dirname -- "$output")"
    "$TOOLCHAIN/$compiler" \
        -O2 -fPIC -fvisibility=hidden -shared \
        -Wl,--no-undefined -Wl,--allow-shlib-undefined \
        -Wl,-soname,libmpvdiag.so \
        "$SOURCE" \
        -L"$TEMP_DIR/aar/jni/$abi" -lmpv \
        -o "$output"
}

build_abi arm64-v8a aarch64-linux-android26-clang
build_abi armeabi-v7a armv7a-linux-androideabi26-clang
