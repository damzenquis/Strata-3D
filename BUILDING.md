# Building Strata:3D

## Requirements

- JDK 17 or newer.
- The Android SDK with platform `android-37.2`, build-tools 36 or newer, **NDK 29.0.14206865** and **CMake 3.22.1**
  (from Android Studio's SDK Manager, or `sdkmanager`).
- For the reconstruction tools: git, curl and a POSIX shell (Git Bash on Windows).
- Optional, for the full test-model suite: Python 3 with `numpy` and `pillow`, and Node.js (see
  [docs/TESTING.md](docs/TESTING.md)).

Create `local.properties` with `sdk.dir=/path/to/Android/sdk` (Android Studio does this for you).

## 1. The reconstruction tools

"Build model" runs native arm64 programs on the phone (MVE, OpenMVS, mvs-texturing and Strata:3D's own tools). They
are built from pinned sources and are not committed:

```bash
tools/recon/build.sh
tools/recon/openmvs/build_openmvs.sh
```

The first build takes about an hour and a half; later builds reuse what was fetched. Details, versions and patches:
[tools/recon/README.md](tools/recon/README.md).

## 2. The app

```bash
./gradlew testDebugUnitTest    # unit tests
./gradlew assembleDebug        # debug APK in app/build/outputs/apk/debug/
./gradlew assembleRelease      # release APK, signed if keystore.properties exists (docs/RELEASING.md)
```

Without the reconstruction tools the build stops with a message. To build without them (everything works except
Build model, which then reports that the phone can't build models), add `-Pstrata.withoutReconTools=true`.

An APK you build yourself is signed with a different key from the official releases, so it cannot update an installed
release (or the other way round) without uninstalling first.
