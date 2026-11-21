# Bundled WebP encoder

`encoder.wasm` is a portable build of [libwebp 1.6.0](https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-1.6.0.tar.gz) with the RGBA entry point in [encode.c](encode.c). The encoder preserves RGB values even under fully transparent pixels. Gradle compiles the WebAssembly module to JVM bytecode during the exporter build and embeds the Chicory runtime with NeoForge Jar-in-Jar.

To regenerate the module, unpack libwebp and [WASI SDK 34](https://github.com/WebAssembly/wasi-sdk/releases/tag/wasi-sdk-34). The libwebp source archive has SHA-256 `e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564`. Run this from the `codec` directory with `WASI_SDK` and `LIBWEBP` pointing to the unpacked directories.

```sh
"$WASI_SDK/bin/clang" -O3 -DNDEBUG -DWEBP_DISABLE_STATS -I "$LIBWEBP" \
  -nostartfiles -Wl,--no-entry -Wl,--export=encode -Wl,--export=malloc \
  -Wl,--export=free -Wl,-z,stack-size=262144 -Wl,--strip-all \
  -o encoder.wasm encode.c "$LIBWEBP"/src/enc/*.c "$LIBWEBP"/src/dsp/*.c \
  "$LIBWEBP"/src/utils/*.c "$LIBWEBP"/sharpyuv/*.c
```

The shipped module has no host imports. Its SHA-256 is `afb64f83ce483557816e3b2834c7f82b20144a67610b40bc3d433c630ab79813`. The exporter JAR includes the [libwebp license](COPYING), [patent grant](PATENTS), and [WASI libc notices](WASI-LICENSE).
