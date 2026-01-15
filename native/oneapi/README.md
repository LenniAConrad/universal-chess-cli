# `native/oneapi`: optional oneAPI backend (JNI)

This directory contains an optional native shared library (`lc0j_oneapi`) used by the Java LC0 evaluator under `src/chess/lc0/`.

If present at runtime, the Java code can:
- Detect whether oneAPI is usable (`chess.lc0.oneapi.Support.isAvailable()` / `deviceCount()`).
- Run LC0J `.bin` policy+value inference on the GPU (`chess.lc0.oneapi.Backend`), which is auto-selected by `chess.lc0.Network` when `-Dcrtk.lc0.backend=auto` (default; legacy: `ucicli.lc0.*`) and oneAPI is available.

This JNI library intentionally has **no third-party Java dependencies**; it uses JNI and the oneAPI SYCL runtime.

## Supported platforms
This should build anywhere CMake can find:
- A C++17 toolchain with SYCL support (`-fsycl`)
- A oneAPI/LLVM SYCL runtime
- A JDK with JNI headers

In practice, Linux is the most tested target. Windows may work with Intel oneAPI; macOS is typically not used.

## Build
Prerequisites:
- CMake 3.18+
- A SYCL toolchain (Intel oneAPI `icpx`/`dpcpp` or LLVM with `-fsycl`)
- Java 17+ JDK (for `jni.h`)

### Linux (single-config generators)

```bash
cmake -S native/oneapi -B native/oneapi/build -DCMAKE_BUILD_TYPE=Release
cmake --build native/oneapi/build -j
```

Output:
- Linux: `native/oneapi/build/liblc0j_oneapi.so`
- Windows: `native/oneapi/build/Release/lc0j_oneapi.dll` (if supported)

If your default compiler does not support `-fsycl`, configure with:

```bash
cmake -S native/oneapi -B native/oneapi/build -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_COMPILER=icpx
```

If CMake cannot find JNI, set `JAVA_HOME` to your JDK root and re-run configure.

## Run
The library must be loadable via `System.loadLibrary("lc0j_oneapi")`.

Two common ways:
- Add the build directory to `java.library.path`.
- Copy the built library next to where you run Java from (there is a small fallback in `chess.lc0.oneapi.Support` that tries the current directory).

Example (quick backend check; opens a window):

```bash
mkdir -p out
javac --release 17 -d out $(find src -name "*.java")
java -cp out -Djava.library.path=native/oneapi/build application.Main display --fen "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1" --show-backend
```

Example (force oneAPI backend; opens a window; errors out if oneAPI cannot initialize):

```bash
java -cp out -Djava.library.path=native/oneapi/build -Dcrtk.lc0.backend=oneapi application.Main display --fen "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1" --show-backend
```

Backend selection:
- Default: `-Dcrtk.lc0.backend=auto` (use CUDA/ROCm/oneAPI if available, else CPU)
- Force CPU: `-Dcrtk.lc0.backend=cpu`
- Force oneAPI: `-Dcrtk.lc0.backend=oneapi` (alias: `intel`)

In code, call `chess.lc0.oneapi.Support.isAvailable()` / `chess.lc0.oneapi.Support.deviceCount()`.

## Notes
- This backend loads LC0J weights with magic `LC0J` (same file format as the pure-Java CPU path).
- `ucicli.lc0.*` and `lc0j.*` are still accepted as legacy aliases, but prefer `crtk.lc0.backend` / `crtk.lc0.threads`.

## Troubleshooting
- VSCode squiggles on `#include <jni.h>` / SYCL headers: run the CMake configure step once to generate `native/oneapi/build/compile_commands.json` and reload VSCode (this repo sets `C_Cpp.default.compileCommands` accordingly).
- `-fsycl` unknown: ensure you are using a SYCL-capable compiler (Intel oneAPI or LLVM SYCL) and set `-DCMAKE_CXX_COMPILER=icpx` if needed.
- CMake cannot find JNI: ensure you installed a full JDK (not just a JRE) and set `JAVA_HOME` to the JDK root.
- `UnsatisfiedLinkError: no lc0j_oneapi in java.library.path`: pass `-Djava.library.path=...` pointing at the directory containing the built library, or copy the library into your working directory.
- `libsycl.so...: cannot open shared object file` (Linux): ensure the oneAPI runtime is installed and discoverable (often via `LD_LIBRARY_PATH=/opt/intel/oneapi/compiler/latest/linux/lib`).
- `deviceCount=0` but you expect a GPU: check Intel GPU drivers, `sycl-ls`, and `ONEAPI_DEVICE_SELECTOR`.
