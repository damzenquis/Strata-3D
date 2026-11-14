#!/usr/bin/env bash
# Cross-compiles OpenMVS (AGPL-3.0) for Android arm64: the app's dense cloud and surface steps. Run build.sh first (it
# installs libjpeg-turbo and libpng in work/arm64).
# Output: work/bin/openmvs/{InterfaceCOLMAP,DensifyPointCloud,ReconstructMesh} (stripped; C++ runtime and OpenMP linked
# statically, so they need only Android system libraries).
# Dependencies, all from source (licences): Boost 1.87 (BSL-1.0), OpenCV 4.11 core/imgproc/imgcodecs/calib3d/
# features2d/flann (Apache-2.0), CGAL 6.0.1 header-only (GPL-3.0+/LGPL-3.0+), VCGLib (GPL-3.0+), nanoflann (BSD-2).
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
NDK="$SDK/ndk/29.0.14206865"
CM="$SDK/cmake/3.22.1/bin"
TC="$NDK/build/cmake/android.toolchain.cmake"
W="$(pwd)/work"
P="$W/arm64"
mkdir -p "$W/src" "$W/bin/openmvs"
clone() { [ -d "$W/src/$2" ] || git -c core.longpaths=true clone -q --depth 1 --branch "$3" "$1" "$W/src/$2"; }

clone https://github.com/cdcseacave/openMVS.git openmvs v2.4.0
clone https://github.com/opencv/opencv.git opencv 4.11.0
clone https://github.com/CGAL/cgal.git cgal v6.0.1
clone https://github.com/jlblancoc/nanoflann.git nanoflann v1.6.2
[ -d "$W/src/vcg" ] || { git -c core.longpaths=true clone -q https://github.com/cdcseacave/VCG.git "$W/src/vcg"; git -C "$W/src/vcg" checkout -q 658ba36d0a5666650da6e066b4794efc5a463407; }
[ -d "$W/src/eigen" ] || { mkdir -p "$W/src/eigen"; curl -sSfL https://gitlab.com/libeigen/eigen/-/archive/3.4.0/eigen-3.4.0.tar.gz | tar -xz -C "$W/src/eigen" --strip-components=1; }
[ -d "$W/src/boost" ] || { mkdir -p "$W/src/boost"; curl -sSfL https://github.com/boostorg/boost/releases/download/boost-1.87.0/boost-1.87.0-cmake.tar.xz | tar -xJ -C "$W/src/boost" --strip-components=1; }
[ -f "$W/src/openmvs/.android-patched" ] || { git -C "$W/src/openmvs" apply --ignore-whitespace "$(pwd)/openmvs/openmvs-android.patch"; touch "$W/src/openmvs/.android-patched"; }

common=(-G Ninja "-DCMAKE_MAKE_PROGRAM=$CM/ninja.exe" "-DCMAKE_TOOLCHAIN_FILE=$TC" -DANDROID_ABI=arm64-v8a
        -DANDROID_PLATFORM=26 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release "-DCMAKE_INSTALL_PREFIX=$P"
        "-DCMAKE_FIND_ROOT_PATH=$P" -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH -DBUILD_SHARED_LIBS=OFF)

# Header-only pieces.
"$CM/cmake" -S "$W/src/eigen" -B "$W/build/eigen" "${common[@]}" -DBUILD_TESTING=OFF -DEIGEN_BUILD_DOC=OFF
"$CM/cmake" --build "$W/build/eigen" --target install
"$CM/cmake" -S "$W/src/nanoflann" -B "$W/build/nanoflann" "${common[@]}" -DNANOFLANN_BUILD_EXAMPLES=OFF -DNANOFLANN_BUILD_TESTS=OFF
"$CM/cmake" --build "$W/build/nanoflann" --target install
"$CM/cmake" -S "$W/src/cgal" -B "$W/build/cgal" "${common[@]}" -DCGAL_DISABLE_GMP=ON -DWITH_examples=OFF -DWITH_demos=OFF -DWITH_tests=OFF
"$CM/cmake" --build "$W/build/cgal" --target install

# Boost: only what OpenMVS links (iostreams with zlib from the NDK; no bzip2/lzma/zstd).
"$CM/cmake" -S "$W/src/boost" -B "$W/build/boost" "${common[@]}" \
  "-DBOOST_INCLUDE_LIBRARIES=iostreams;program_options;system;serialization;graph;math;pool;property_map;throw_exception;multiprecision" \
  -DBOOST_IOSTREAMS_ENABLE_BZIP2=OFF -DBOOST_IOSTREAMS_ENABLE_LZMA=OFF -DBOOST_IOSTREAMS_ENABLE_ZSTD=OFF -DBOOST_ENABLE_PYTHON=OFF
"$CM/cmake" --build "$W/build/boost" --target install
# CGAL uses header-only Boost libraries beyond those above: install every Boost header.
for d in "$W"/src/boost/libs/*/include/boost "$W"/src/boost/libs/numeric/*/include/boost; do [ -d "$d" ] && cp -rn "$d" "$P/include/"; done

# OpenCV: the modules OpenMVS uses, no extras.
"$CM/cmake" -S "$W/src/opencv" -B "$W/build/opencv" "${common[@]}" \
  -DBUILD_LIST=core,imgproc,imgcodecs,calib3d,features2d,flann -DBUILD_ANDROID_PROJECTS=OFF -DBUILD_ANDROID_EXAMPLES=OFF \
  -DBUILD_JAVA=OFF -DBUILD_opencv_java=OFF -DBUILD_TESTS=OFF -DBUILD_PERF_TESTS=OFF -DBUILD_EXAMPLES=OFF -DBUILD_DOCS=OFF \
  -DWITH_OPENEXR=OFF -DWITH_TIFF=OFF -DWITH_WEBP=OFF -DWITH_OPENJPEG=OFF -DWITH_JASPER=OFF -DWITH_IPP=OFF -DWITH_ITT=OFF \
  -DWITH_PROTOBUF=OFF -DWITH_ADE=OFF -DWITH_EIGEN=ON "-DEigen3_DIR=$P/share/eigen3/cmake" -DBUILD_JPEG=ON -DBUILD_PNG=ON -DBUILD_ZLIB=OFF \
  -DOPENCV_GENERATE_PKGCONFIG=OFF -DBUILD_FAT_JAVA_LIB=OFF -DWITH_KLEIDICV=OFF -DWITH_CAROTENE=ON
"$CM/cmake" --build "$W/build/opencv" --target install

# OpenMVS: command-line tools only (no viewer, CUDA, Python, breakpad). Its CMake drops CMAKE_EXE_LINKER_FLAGS, so the
# static-runtime link flags go in CMAKE_CXX_FLAGS (they reach the link line from there).
OCV_DIR="$(dirname "$(find "$P" -name OpenCVConfig.cmake | head -1)")"
"$CM/cmake" -S "$W/src/openmvs" -B "$W/build/openmvs" "${common[@]}" \
  "-DCMAKE_CXX_FLAGS=-Wno-error=missing-template-arg-list-after-template-kw -static-openmp -static-libstdc++ -Wno-unused-command-line-argument" "-DOpenMP_omp_LIBRARY=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/lib/clang/*/lib/linux/aarch64 | head -1)/libomp.a" -DCGAL_DISABLE_GMP=ON -DOpenMVS_BUILD_VIEWER=OFF -DOpenMVS_USE_CUDA=OFF -DOpenMVS_USE_PYTHON=OFF -DOpenMVS_USE_BREAKPAD=OFF \
  -DOpenMVS_USE_SSE=OFF -DOpenMVS_ENABLE_TESTS=OFF -DOpenMVS_ENABLE_IPO=OFF -DOpenMVS_USE_OPENMP=ON \
  "-DOpenCV_DIR=$OCV_DIR" "-DEigen3_DIR=$P/share/eigen3/cmake" "-DBoost_DIR=$(dirname "$(find "$P" -name BoostConfig.cmake | head -1)")" \
  "-DCGAL_DIR=$P/lib/cmake/CGAL" "-DVCG_ROOT=$W/src/vcg" "-DVCG_DIR=$W/src/vcg" "-Dnanoflann_DIR=$P/share/nanoflann/cmake" \
  "-DJPEG_ROOT=$P" "-DPNG_ROOT=$P"
"$CM/cmake" --build "$W/build/openmvs" --target InterfaceCOLMAP DensifyPointCloud ReconstructMesh

STRIP="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)/llvm-strip"
for t in InterfaceCOLMAP DensifyPointCloud ReconstructMesh; do
  "$STRIP" -o "$W/bin/openmvs/$t" "$(find "$W/build/openmvs" -name "$t" -type f | head -1)"
done
# The app packages them under these names (app/build.gradle.kts, reconTools).
cp "$W/bin/openmvs/InterfaceCOLMAP" "$W/bin/omvs_import"
cp "$W/bin/openmvs/DensifyPointCloud" "$W/bin/omvs_densify"
cp "$W/bin/openmvs/ReconstructMesh" "$W/bin/omvs_mesh"
ls -l "$W/bin/openmvs"
