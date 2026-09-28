# Subtitle alignment native bridge

This small JNI library uses [`alass-core` 2.0.0](https://github.com/kaegi/alass)
for constant-offset subtitle alignment and WebRTC VAD to obtain voice spans from
low-rate PCM. The Rust source and lockfile are included here; prebuilt Android
libraries in `app/src/main/jniLibs` let normal Gradle release builds run without
installing Rust. ALASS is GPL-3.0-or-later, compatible with this project's GPL.

To rebuild the libraries, install the Rust Android targets
`armv7-linux-androideabi` and `aarch64-linux-android`, and Android NDK 27 or
newer. Set each target's Cargo linker and C compiler to the matching NDK
`*-clang` binary (API 26), then run `cargo build --release --locked --target`
for each target and copy `libsaab_subtitle_sync.so` to the corresponding
`app/src/main/jniLibs` ABI directory.

The app probes only a bounded portion of a lightweight reference stream after
video has begun playing. It applies an offset only when speech overlap improves
substantially. Complex drift, edited cuts, non-SRT timing formats, unreachable
streams, or weak voice matches retain the original subtitle timing; users can
still adjust subtitle delay manually.
