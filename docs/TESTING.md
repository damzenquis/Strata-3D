# Testing

## Unit tests

```bash
./gradlew testDebugUnitTest
```

| Test class | What it covers |
|---|---|
| `StlParserTest`, `ObjParserTest`, `PlyParserTest`, `ThreeMfParserTest`, `GltfParserTest` | Each parser's features and error handling, using small in-memory files |
| `ModelResourcesTest` | Relative-URI decoding used for glTF companion files |
| `MeasurementTest`, `RaycasterTest` | Measurement maths and ray picking |
| `FileLocationHelperTest` | Readable paths from content URIs |
| `TestModelsTest` | Every file in `test-models/` against `expected.json`: triangle count, bounds and surface area |
| `MeshoptReferenceTest` | Every meshopt buffer in the test models, decoded byte-for-byte against meshoptimizer's reference decoder |

`TestModelsTest` and `MeshoptReferenceTest` are **skipped** when `test-models/` has not been generated. A readable
report is written to `app/build/reports/test-models.txt`.

## The test-model suite

`test-models/` is generated (about 80 MB) and is not committed. Build it with:

```bash
pip install numpy pillow
npm install --prefix .tools @gltf-transform/cli@4.5.1      # Draco/meshopt/quantize tooling

# macOS / Linux
export GLTF_TRANSFORM=.tools/node_modules/.bin/gltf-transform
export MESHOPTIMIZER_DIR=.tools/node_modules/meshoptimizer
python tools/generate_test_models.py

# Windows (PowerShell)
$env:GLTF_TRANSFORM = ".tools\node_modules\.bin\gltf-transform.cmd"
$env:MESHOPTIMIZER_DIR = ".tools\node_modules\meshoptimizer"
python tools/generate_test_models.py
```

Without the two environment variables, the Draco, quantized and meshopt cases are skipped and everything else is
still generated.

Every expected value (triangle count, bounding box and surface area) is computed by the generator from the
source geometry, independently of the app's parsers. The cases are grouped into tiers:

- **core:** what the format support promises.
- **real-world:** structures real tools produce, such as slicer 3MF projects and CAD STL headers.
- **limitation:** unsupported or broken input that must fail with a clear message, never a crash.

## On a device or emulator

1. `./gradlew installDebug` (or install the release APK).
2. Copy test models to the device, for example `adb push test-models/gltf /sdcard/Download/StrataTests`.
   After regenerating the models, push them again: copies already on the device keep the old geometry.
3. Open them through the gear panel → **Open 3D model**, by "Open with" from a file manager, and by Share.

Before every release, check at least: one model per format, a large model (1M triangles), a `.gltf` + `.bin`
multi-select, a `.zip`, an invalid file, light and dark mode, rotation, and the measurement tool.

## Mesh check fixtures

`MeshAnalyzerTest` builds valid and deliberately broken meshes in code: closed, open, non-manifold, flipped,
inside-out, degenerate, hollow, multi-part and Möbius. It also analyzes every generated test model and checks the
expected diagnosis.

`stl/35_inside_out_torus_binary.stl` is a closed torus with every triangle reversed: the deliberate inside-out case.
Before 0.8.0 the generator also wound its torus-knot tubes inwards by mistake (found by the mesh check, confirmed by an
independent signed-volume calculation); that is fixed, and every knot now checks clean. Strips, fans and the meshopt
strip sequence are open surfaces by design.

## Benchmarking the mesh check on a phone

Debug builds log every analysis to logcat with the tag `StrataDiag`. Each line has the triangle count, the time, the
sampled Java-heap growth, the heap limit and the device model. To collect them:

1. Install a debug build on the phone: `./gradlew installDebug`.
2. Copy the large fixtures to the phone, for example to `Download/StrataTests/`:
   - `04_torus_knot_1M_binary.stl`
   - `20_torus_knot_500k.glb`
   - `16_torus_knot_300k.3mf`
   - `07_sphere_199k_v_vn.obj`
   - `09_torus_102k_binary_le.ply`
3. Open each model, then go to Model info → Mesh check → Analyze.
4. Collect the results:

   ```bash
   adb logcat -d -s StrataDiag
   ```

The first analysis after launch includes JIT warm-up. For steady numbers, analyze a second model before recording.

## Capture on a phone (CameraX)

Install the debug build (`app.strata3d.debug`, alongside the release app), grant the camera
(`adb shell pm grant app.strata3d.debug android.permission.CAMERA`), and keep the screen on (`adb shell svc power
stayon true`). The engine logs one line per photo with its size, rotation and time from shutter to saved file:

```bash
adb logcat -d | grep "CameraXCapture"
```

Captures live in the app's private folder; read them with `run-as`:

```bash
adb shell "run-as app.strata3d.debug ls files/captures"
```

**Checks and results** (Galaxy A17 5G, 2026-11-11, build `ce6c399`):

| Check | Result |
|---|---|
| Open and close the capture screen 20 times (tap **Capture an object**, wait 3 s, Back) | 20/20 bound at 4080×3060, one process throughout, no crash. Only the camera driver's teardown messages are logged |
| Smart shutter, phone lying still after the tap | Exactly 1 photo, no duplicates |
| Manual shutter, 5 presses as fast as possible | 1 photo; presses while a photo is saving are dropped |
| Manual shutter, 5 presses 1.5 s apart | 5 photos |
| Time from shutter to saved file (12.5 MP JPEG) | 320–450 ms |
| Saved data | Format 2, EXIF orientation 6 = `rotationDegrees` 90, `focal_length` intrinsics (fx 3125), exposure and ISO from EXIF, no GPS. Gravity matches the phone's pose (upright, camera 14° below horizontal) |
| Summary screen | Two-ring dial; "Missing: some angles from above" for a low-only capture |

Still to test by hand: walking around an object with the smart shutter (spacing, "Hold steady", coverage), and
pausing the app mid-capture.
