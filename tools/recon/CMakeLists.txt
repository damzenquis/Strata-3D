# Spike: cross-compile MVE (BSD-3, rev bf2279f161) for Android arm64 as standalone command-line tools.
cmake_minimum_required(VERSION 3.18)
project(mve_android CXX C)

set(MVE ${CMAKE_CURRENT_SOURCE_DIR}/work/src/mve)
set(DEPS ${CMAKE_CURRENT_SOURCE_DIR}/work/arm64)
set(CMAKE_CXX_STANDARD 17)

# MVE's own flags (Makefile.inc) minus -march=native; TIFF support disabled (not needed for the pipeline).
add_compile_options(-O3 -funsafe-math-optimizations -fno-math-errno -fopenmp -Wno-deprecated-declarations)
add_compile_definitions(MVE_NO_TIFF_SUPPORT)
include_directories(${MVE}/libs ${DEPS}/include)

function(mve_lib name)
  file(GLOB srcs ${MVE}/libs/${name}/*.cc)
  list(FILTER srcs EXCLUDE REGEX "/_[^/]*\\.cc$")   # leading underscore = tests/tools
  add_library(mve_${name} STATIC ${srcs})
endfunction()
foreach(l util mve sfm dmrecon fssr)
  mve_lib(${l})
endforeach()

set(MVE_LIBS mve_fssr mve_dmrecon mve_sfm mve_mve mve_util)
foreach(app makescene sfmrecon dmrecon scene2pset fssrecon meshclean)
  file(GLOB app_srcs ${MVE}/apps/${app}/*.cc)
  add_executable(${app} ${app_srcs})
  target_link_libraries(${app} PRIVATE ${MVE_LIBS} ${DEPS}/lib/libpng16.a ${DEPS}/lib/libjpeg.a z -fopenmp -static-openmp)
endforeach()

# Strata:3D's own tool: object bounding box from the SfM cameras (objbox/objbox.cc), needed before the depth stage.
add_executable(objbox objbox/objbox.cc)
target_link_libraries(objbox PRIVATE mve_mve mve_util ${DEPS}/lib/libpng16.a ${DEPS}/lib/libjpeg.a z -fopenmp -static-openmp)
# Strata:3D's own tool: removes the supporting surface (desk, floor) from a mesh of an objbox-aligned scene.
add_executable(groundcut objbox/groundcut.cc)
target_link_libraries(groundcut PRIVATE mve_mve mve_util ${DEPS}/lib/libpng16.a ${DEPS}/lib/libjpeg.a z -fopenmp -static-openmp)
