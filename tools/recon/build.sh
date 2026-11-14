#!/usr/bin/env bash
# Reconstruction tools: fetches pinned sources and cross-compiles MVE's pipeline tools for Android arm64.
# The app packages makescene, sfmrecon, objbox, groundcut, meshclean and texrecon (see README.md).
# Output: work/bin/{makescene,sfmrecon,dmrecon,scene2pset,fssrecon,meshclean,objbox,groundcut} (stripped, static C++ runtime).
# Requires: Android SDK with NDK 29.0.14206865 and CMake 3.22.1; git; bash (Git Bash on Windows; clones use
# core.longpaths, since oneTBB has paths longer than the Windows default limit).
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
NDK="$SDK/ndk/29.0.14206865"
CM="$SDK/cmake/3.22.1/bin"
TC="$NDK/build/cmake/android.toolchain.cmake"
W="$(pwd)/work"
mkdir -p "$W/src" "$W/bin"

# Pinned revisions (licences: MVE BSD-3-Clause, libjpeg-turbo IJG/BSD, libpng libpng-2.0).
[ -d "$W/src/mve" ] || { git -c core.longpaths=true clone -q https://github.com/simonfuhrmann/mve.git "$W/src/mve"; git -C "$W/src/mve" checkout -q bf2279f161ba962072ecac85224c15e82bc5f52e; git -C "$W/src/mve" apply --ignore-whitespace "$(pwd)/mve-android.patch"; }
[ -d "$W/src/libjpeg-turbo" ] || git -c core.longpaths=true clone -q --depth 1 --branch 3.2.1 https://github.com/libjpeg-turbo/libjpeg-turbo.git "$W/src/libjpeg-turbo"
[ -d "$W/src/libpng" ] || git -c core.longpaths=true clone -q --depth 1 --branch v1.6.59 https://github.com/pnggroup/libpng.git "$W/src/libpng"

common=(-G Ninja "-DCMAKE_MAKE_PROGRAM=$CM/ninja.exe" "-DCMAKE_TOOLCHAIN_FILE=$TC" -DANDROID_ABI=arm64-v8a
        -DANDROID_PLATFORM=26 -DCMAKE_BUILD_TYPE=Release "-DCMAKE_INSTALL_PREFIX=$W/arm64")
"$CM/cmake" -S "$W/src/libjpeg-turbo" -B "$W/build/jpeg" "${common[@]}" -DENABLE_SHARED=OFF -DWITH_TURBOJPEG=OFF -DWITH_TOOLS=OFF -DWITH_TESTS=OFF
"$CM/cmake" --build "$W/build/jpeg" --target install
"$CM/cmake" -S "$W/src/libpng" -B "$W/build/png" "${common[@]}" -DPNG_SHARED=OFF -DPNG_TESTS=OFF -DPNG_TOOLS=OFF -DPNG_FRAMEWORK=OFF
"$CM/cmake" --build "$W/build/png" --target install
"$CM/cmake" -S . -B "$W/build/mve" "${common[@]}" -DANDROID_STL=c++_static
"$CM/cmake" --build "$W/build/mve"

STRIP="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)/llvm-strip"
for t in makescene sfmrecon dmrecon scene2pset fssrecon meshclean objbox groundcut; do "$STRIP" -o "$W/bin/$t" "$W/build/mve/$t"; done
echo "Built: $W/bin"

# ---- Texturing: mvs-texturing (BSD-3) with its MVE fork, mapMAP (BSD-3), rayint (BSD-3), Eigen 3.4.0 (MPL2), oneTBB (Apache-2.0)
# mvs-texturing builds against the same upstream MVE as the other tools (one MVE in the app); its patch adapts two
# call sites to MVE's current API (NVM camera info type, an ambiguous linear_at overload).
[ -d "$W/src/mvs-texturing" ] || { git -c core.longpaths=true clone -q https://github.com/nmoehrle/mvs-texturing.git "$W/src/mvs-texturing"; git -C "$W/src/mvs-texturing" checkout -q f3374298ac959cb5afe47a14e4d35d2ac7fbdbb1; git -C "$W/src/mvs-texturing" apply --ignore-whitespace "$(pwd)/texturing/mvs-texturing-upstream-mve.patch"; }
[ -d "$W/src/mapmap" ] || { git -c core.longpaths=true clone -q https://github.com/dthuerck/mapmap_cpu.git "$W/src/mapmap"; git -C "$W/src/mapmap" checkout -q fa526e0; }
[ -d "$W/src/rayint" ] || { git -c core.longpaths=true clone -q https://github.com/nmoehrle/rayint.git "$W/src/rayint"; git -C "$W/src/rayint" checkout -q b62c8905f6dd11a179128517e814e0e00f9bb809; }
[ -d "$W/src/eigen" ] || { mkdir -p "$W/src/eigen"; curl -sL https://gitlab.com/libeigen/eigen/-/archive/3.4.0/eigen-3.4.0.tar.gz | tar -xz -C "$W/src/eigen" --strip-components=1; }
[ -d "$W/src/onetbb" ] || git -c core.longpaths=true clone -q --depth 1 --branch v2023.2.0 https://github.com/uxlfoundation/oneTBB.git "$W/src/onetbb"
"$CM/cmake" -S "$W/src/onetbb" -B "$W/build/tbb" "${common[@]}" -DANDROID_STL=c++_static -DBUILD_SHARED_LIBS=OFF -DTBB_TEST=OFF -DTBB_EXAMPLES=OFF -DTBBMALLOC_BUILD=OFF -DTBB_STRICT=OFF
"$CM/cmake" --build "$W/build/tbb" --target install
"$CM/cmake" -S texturing -B "$W/build/tex" "${common[@]}" -DANDROID_STL=c++_static
"$CM/cmake" --build "$W/build/tex"
"$STRIP" -o "$W/bin/texrecon" "$W/build/tex/texrecon"
echo "Built texrecon: $W/bin/texrecon"
