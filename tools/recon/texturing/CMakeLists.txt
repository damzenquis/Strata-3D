# Spike: cross-compile mvs-texturing's texrecon (BSD-3, rev f3374298ac) for Android arm64.
cmake_minimum_required(VERSION 3.18)
project(texrecon_android CXX C)

set(SRC ${CMAKE_CURRENT_SOURCE_DIR}/../work/src)
set(DEPS ${CMAKE_CURRENT_SOURCE_DIR}/../work/arm64)
set(CMAKE_CXX_STANDARD 14)

add_compile_options(-O3 -fopenmp -Wno-deprecated-declarations)
add_compile_definitions(EIGEN_MPL2_ONLY MVE_NO_TIFF_SUPPORT)
include_directories(
  ${SRC}/mvs-texturing/libs
  ${SRC}/rayint/libs
  ${SRC}/mve/libs
  ${SRC}/eigen
  ${SRC}/mapmap
  ${SRC}/mapmap/mapmap
  ${SRC}/mapmap/ext/dset
  ${DEPS}/include)

# MVE core libraries (same sources as the MVE spike build).
foreach(l util mve)
  file(GLOB srcs ${SRC}/mve/libs/${l}/*.cc)
  list(FILTER srcs EXCLUDE REGEX "/_[^/]*\\.cc$")
  add_library(mve_${l} STATIC ${srcs})
endforeach()

file(GLOB tex_srcs ${SRC}/mvs-texturing/libs/tex/*.cpp)
add_library(tex STATIC ${tex_srcs})

file(GLOB app_srcs ${SRC}/mvs-texturing/apps/texrecon/*.cpp)
add_executable(texrecon ${app_srcs})
target_link_libraries(texrecon PRIVATE tex mve_mve mve_util ${DEPS}/lib/libtbb.a ${DEPS}/lib/libpng16.a ${DEPS}/lib/libjpeg.a z -fopenmp -static-openmp)
