# Reconstruction tools (Android arm64)

"Build model" in the app runs a photogrammetry pipeline entirely on the phone. Its programs are native arm64
executables, built here from source and packaged into the APK (`app/build.gradle.kts`, task `prepareReconTools`, as
`lib/arm64-v8a/libmve_<tool>.so`). They are not committed: build them once before building the app.

## What the app runs

| Stage | Tool | From |
|---|---|---|
| Import photos | `makescene` | MVE |
| Place the photos (structure from motion) | `sfmrecon` | MVE |
| Find the object, turn the scene upright | `objbox` | this folder (`objbox/objbox.cc`) |
| Depth maps and dense cloud | `omvs_import`, `omvs_densify` | OpenMVS (`InterfaceCOLMAP`, `DensifyPointCloud`) |
| Surface | `omvs_mesh` | OpenMVS (`ReconstructMesh`) |
| Remove the surface underneath | `groundcut` | this folder (`objbox/groundcut.cc`) |
| Clean up | `meshclean` | MVE |
| Colour from the photos | `texrecon` | mvs-texturing |

How they fit together, and the settings the app uses, is described in
[docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md) ("On-device reconstruction").

## Building

Requirements: Git Bash (on Windows) or any POSIX shell, git, curl, and the Android SDK with **NDK 29.0.14206865**
and **CMake 3.22.1** (`ANDROID_HOME`, or the default `%LOCALAPPDATA%/Android/Sdk`).

```bash
tools/recon/build.sh                    # MVE, objbox, groundcut, mvs-texturing (about 20 minutes)
tools/recon/openmvs/build_openmvs.sh    # OpenMVS and its libraries (about an hour the first time)
```

Both fetch pinned sources into `tools/recon/work/` (git-ignored), cross-compile them for Android arm64 with a static
C++ runtime, and write the programs to `tools/recon/work/bin/`. The app build then picks them up; it fails with a
clear message if they are missing.

| File | What |
|---|---|
| `build.sh`, `CMakeLists.txt` | MVE (MVE ships only Makefiles), `objbox`, `groundcut`; then mvs-texturing |
| `mve-android.patch` | `makescene` used TIFF loaders even with TIFF disabled |
| `texturing/` | Build files for mvs-texturing, and a two-line patch for MVE's current API |
| `objbox/` | Strata:3D's own tools: the object box (with the user's tap and the phone's gravity) and the ground cut |
| `openmvs/build_openmvs.sh` | OpenMVS with Boost, OpenCV (six modules), CGAL (header-only, without GMP), VCGLib, nanoflann |
| `openmvs/openmvs-android.patch` | Android fixes: optional pkg-config, the compiler check, `uint_t`, console handles, no zstd |

## Pinned sources

| Component | Version | Licence |
|---|---|---|
| MVE | `bf2279f161` | BSD-3-Clause |
| mvs-texturing | `f3374298ac` | BSD-3-Clause |
| mapMAP | `fa526e0` | BSD-3-Clause |
| rayint | `b62c8905f6` | BSD-3-Clause |
| Eigen | 3.4.0 | MPL-2.0 (built with `EIGEN_MPL2_ONLY`) |
| oneTBB | v2023.2.0 | Apache-2.0 |
| libjpeg-turbo | 3.2.1 | IJG/BSD |
| libpng | v1.6.59 | libpng-2.0 |
| OpenMVS | v2.4.0 | AGPL-3.0 |
| Boost | 1.87.0 | BSL-1.0 |
| OpenCV | 4.11.0 | Apache-2.0 |
| CGAL | 6.0.1 | GPL-3.0+ / LGPL-3.0+ |
| VCGLib | `658ba36` | GPL-3.0+ |
| nanoflann | 1.6.2 | BSD-2-Clause |

The licence texts ship in the app (`app/src/main/assets/THIRD_PARTY_NOTICES.txt`, shown under Settings › License).

## Measured on a Galaxy A17 5G (Exynos 1330, 4 GB)

| Object | Photos placed | Whole build | Peak memory |
|---|---|---|---|
| Glass bottle, 58 photos | 49–50 | about 4.5–5 minutes | about 300 MB in the dense stage |
| Cleaning-spray bottle, 44 photos (five builds) | 36–39 | 197–234 s | |
| Matte statue, 77 photos placed | 77 | dense cloud 4.6 min, surface 6 s | about 300 MB |

Plain, shiny and see-through surfaces give the photos little to match: such areas can come out with holes or as flat,
guessed patches. Good, even light and a textured background help the most.
