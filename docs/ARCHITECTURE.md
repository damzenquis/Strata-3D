# Architecture

Strata:3D is an Android app in Kotlin, with Jetpack Compose for the UI and OpenGL ES 3.0 for rendering. It has two
activities:
- `MainActivity`, the viewer;
- `CaptureActivity`, guided capture, kept separate so that the camera loads only when capture opens.

It has no network access and no database. Its one runtime dependency beyond AndroidX (including CameraX) is the
Openize Drako decoder.

## Packages

| Package | Responsibility | Android? |
|---|---|---|
| `app.strata3d.model` | File parsing and decoding: STL, OBJ, PLY, 3MF, glTF/GLB, `.zip` packages, Draco, meshopt. Produces a `Mesh`. | **No.** Pure JVM, so it is fully unit-testable. |
| `app.strata3d.render` | `ModelSurfaceView` (GLSurfaceView + gestures), `ModelRenderer` (GL thread), `OrbitCamera`, `Raycaster` and `MeasurePicker` (measurement picking), GLSL `Shaders`. | Yes |
| `app.strata3d.ui` | `ViewerScreen` (Compose UI), `ViewerViewModel` (state + loading), `theme/` (colours, persisted theme choice). | Yes |
| `app.strata3d.util` | `FileLocationHelper`: turns content URIs into readable paths. | Yes |
| `app.strata3d.capture.format` | Capture dataset (v2, reads v1): model, JSON, crash-safe `CaptureStore`, pose maths, COLMAP/nerfstudio export and `.zip`. | **No** |
| `app.strata3d.capture.shutter` | `SmartShutter` (when to take a photo), `CoverageRings` (coverage dial), `MotionSensors` (sensor thread). | Pure, except `MotionSensors` |
| `app.strata3d.capture.quality` | `Sharpness` (variance of the Laplacian). | **No** |
| `app.strata3d.capture.camera` | `CameraGeometry` (tap mapping, intrinsics; pure), `DeviceGuards` (heat, storage). | Partly |
| `app.strata3d.capture` / `.capture.ui` | `CameraXCapture` (CameraX, sensors, shutter, writing), `CaptureActivity` with library, capture and summary screens, `BuildModelCard`. | Yes |
| `app.strata3d.capture.recon` | On-device reconstruction: `ReconPlan` and `ReconStatus` (pure), `ReconRunner` (runs the pipeline tools), `ReconService` (foreground service, process `:recon`). | Partly |

The `model` package must never import `android.*`. That rule keeps the parsers testable with plain JUnit, and it
is why companion files (a glTF's `.bin`) arrive through the small `ModelResources` interface rather than a
`ContentResolver`.

## Data flow

```
picker / Open with / Share ──► MainActivity.handleIntent ──► ViewerViewModel.load(uris)
                                                                  │  (Dispatchers.IO)
                                                                  ▼
                       copy model to cache ──► ModelParser.parse(stream, name, size, resources)
                                                                  │
                                         format by extension, else content sniffing
                                                                  ▼
                                 StlParser / ObjParser / PlyParser / ThreeMfParser / GltfParser / ZipPackage
                                                                  │
                                                                  ▼
                                   Mesh (triangle soup FloatBuffer + bounds + stats)
                                                                  │
                                       ViewerUiState.model ──► ViewerScreen ──► ModelSurfaceView
                                                                                    │ (GL thread)
                                                                                    ▼
                                                                             ModelRenderer
```

- **`Mesh`** is a triangle soup: a direct, native-order `FloatBuffer` holding x, y, z for three vertices per triangle.
  It has no indices and no stored normals; the fragment shader derives flat normals from screen-space derivatives.
  That keeps memory at 36 bytes per triangle and makes uploads a single `glBufferData`.
- **`MeshBuilder`** accumulates triangles, grows its buffer geometrically, drops non-finite triangles, and computes
  bounds, volume and surface area in one pass at the end.
- **Loading** runs on `Dispatchers.IO` inside `viewModelScope`. Opening a new file cancels any load still in
  progress. Out-of-memory, permission and parse errors become a snackbar message, and the previous model stays open.
- **Rendering** happens in `GLSurfaceView.RENDERMODE_WHEN_DIRTY`, so frames are drawn only when something changes.
  The GL context survives rotation through `configChanges` in the manifest.

## Parsers at a glance

| Parser | Strategy |
|---|---|
| `StlParser` | Detects binary vs ASCII by size arithmetic, not by the misleading `solid` header. Streams triangles. |
| `ObjParser` | Byte-level line reader with no per-line allocations. Fan-triangulates n-gons, supports negative indices. |
| `PlyParser` | Reads the header into an element/property table, then streams ASCII or binary (either endianness). |
| `ThreeMfParser` | Reads every `*.model` part in the ZIP with SAX, finds the root part from `_rels/.rels`, and resolves `<components>` (including cross-part `p:path`) with composed transforms. |
| `GltfParser` | Minimal JSON DOM (`JsonParser`). Walks scene → nodes → meshes → primitives. Buffers load lazily; buffer views go through a resolver that transparently decodes meshopt views. Draco primitives are decoded with Openize Drako. |
| `ZipPackage` | Unpacks a `.zip` in memory (1 GB cap), picks the model by extension preference, and serves the other entries as `ModelResources`. |
| `MeshoptDecoder` | Port of meshoptimizer's reference decoder, verified byte-for-byte in tests. |

## Camera and viewer coordinates

World space is the model's own space, with **+Z up**. This is the 3D-printing and STL convention. glTF and GLB are
+Y up by definition, so `GltfParser` turns the whole scene +90° about X, (x, y, z) → (x, −z, y), and glTF models
stand upright like every other format. The app's own GLB export does the opposite turn, so exported models reopen
upright. (`upright = false` reads the file's own frame, for parsing tests.) The camera's standard views are:

| View | Camera looks along | Screen right | Screen up |
|---|---|---|---|
| Front | +Y (camera on the −Y side) | +X | +Z |
| Side (right) | −X (camera on the +X side) | +Y | +Z |
| Top | −Z (camera above) | +X | +Y |
| Iso | from the +X, −Y, +Z octant (yaw −45°, pitch 35.26°) | | |
| Default (load, double-tap) | yaw −60°, pitch 25° | | |

`OrbitCamera` orbits a target point by yaw (around +Z) and pitch (−90° to 90°). Its matrix maths lives in `Mat4`,
which is plain Kotlin, so camera behaviour is unit-tested (`OrbitCameraTest`).

- **Projection:** orthographic uses a visible half-height of `distance × tan(fov/2)`, exactly the size of the
  perspective frustum at the target plane. Toggling therefore never changes the model's size at the orbit centre.
  The ortho depth range extends on both sides of the eye, so zooming in never clips the model.
- **Framing:** Fit and the presets fit all eight corners of the model's (already transformed) bounding box. Load and
  reset keep the original bounding-sphere fit.
- **Obstructed areas:** the UI reports the screen areas covered by floating controls (`setObstructedInsets`).
  Framing fits into the remaining rectangle, and a lens shift (an off-centre projection) centres the image there.
  The camera still orbits the model's centre.
- **Transitions:** presets, Fit and double-tap reset ease out over 250 ms. The render loop advances them
  (`advance()`) and requests frames only while one is running. A new touch cancels a transition before gesture
  detection runs, so a double-tap can start its own transition.
- **Picking:** perspective rays start at the eye; orthographic rays start on the near plane and share the view direction.

## Measurement

- **Units:** `model/ModelScale` resolves the unit of the coordinates (user choice → file → assumed mm) and converts
  for display; `LengthFormat` formats. Points and distances stay in model coordinates; only the display converts, so
  changing units updates a finished measurement at once. The user's choice is stored per Recent Files entry (an
  optional `unit` key; schema still version 1).
- **Picking** (`render/MeasurePicker`, pure, run on a background thread with a `PickFrame` copy of the camera):
  1. The tap ray finds the nearest surface hit (`Raycaster`, exact, double-sided, with a scale-relative parallel test
     so metre- and micron-scale models pick the same as millimetre ones).
  2. With Snap on, every vertex is projected to the screen. The nearest four distinct positions within 18 dp are
     candidates.
  3. A candidate is accepted only if a ray from the camera reaches it unobstructed. This rejects vertices on the
     back of the model or behind another part.
  4. With no visible candidate, the free surface point is used.

  Snapped points draw as filled markers and free points as hollow ones. Only the original triangles are pickable.
- **With a section:**
  - `Raycaster.intersect(mesh, ray, section, capped)` skips hits on the removed side, exactly as the shader
    discards them.
  - When the cut is filled, it adds up the same front/back winding count as the stencil pass. If the count is not
    zero and the ray crosses the plane before any kept hit, the result is `onCap`.
  - `MeasurePicker` treats `onCap` as nothing to pick and as an occluder for vertex visibility. It also skips
    cut-away snap candidates.
  - `ModelSurfaceView` passes the section and fill exactly as drawn, including stencil support and the Wireframe
    exception.
- **Cost:** one ray cast plus one projection pass, plus up to four ray casts with Snap on. Measured times with Snap
  on, each the time for one tap:

  | Where | Mesh | Time per tap |
  |---|---|---|
  | JVM | 1M triangles | ~35 ms |
  | Emulator (software GPU, x86 host) | 200k triangles | ~25 ms (126 ms on the first tap, while the JIT warms up) |
  | Emulator (software GPU, x86 host) | 102k triangles | ~10 ms |

## Cross-section clipping

- **Plane:** `render/SectionPlane` (pure, unit-tested) is an axis-aligned plane in world coordinates, the same
  coordinates as the camera presets and measurements.
  - The slider sets a fraction of the model's extent; the bar shows the position converted with `ModelScale`.
  - `equation()` returns `(a, b, c, d)`, and a point is kept when `a·x + b·y + c·z + d ≥ 0`. The default kept side
    is X ≤ p, Y ≥ p, Z ≤ p, so the default camera (front-right, above) looks at the cut. Flip keeps the other side.
- **Shader:** the mesh shader has a `SECTION` variant (`Shaders.withSection`) with a world-position varying and a
  `discard` against the plane. It is a separate program, created on first use. A shader that can discard may lose
  early depth testing on some GPUs, so normal viewing keeps the original program. Snapshots use the same draw path,
  so they show the cut.
- **Geometry:** the mesh data is never modified. Through the cut you see the inner (back) faces until capping is
  added.
- **Cost:** on the emulator's software GPU, 1M triangles rotating take a median 50.7 ms per frame without the
  section and 52.4 ms with it (+3%).

### Section capping

`render/SectionCap` (pure, unit-tested) and `ModelRenderer.drawCap`:

- **Count pass:** right after the clipped mesh is drawn, it is drawn again into the stencil buffer only, using a
  minimal program that has just the plane test. Depth testing is off, front faces add 1 (`INCR_WRAP`) and back
  faces subtract 1 (`DECR_WRAP`).
- **Cap pass:** a quad on the plane, covering the model's bounds plus 2%, is drawn where the stencil is not zero.
  It is depth-tested, so nearer kept geometry still hides it.
- **Why winding numbers, not parity:**
  - a correctly modelled cavity cancels to 0 and stays open;
  - overlapping closed parts fill as their union (parity would leave the overlap empty);
  - an inside-out solid is still filled.
- **Policy** (`SectionCap.reliability`): fill only with a stencil buffer *and* a mesh check that found no boundary,
  non-manifold, flipped or non-orientable geometry. Zero-area triangles and inside-out parts are harmless. Without
  that, the uncapped section is shown.
- **Automatic check:** opening Section starts the mesh check when it hasn't run yet (`ViewerViewModel.toggleSection`),
  and closing Section cancels it if Section started it. The result is the same cached report Model info shows. The
  renderer reports stencil support (`onStencilSupport`), so the Section bar can explain an unfilled cut.
- **Device support:** the EGL chooser asks for 8-bit stencil + 4× MSAA, then MSAA without stencil, then stencil
  without MSAA, then neither. `GL_STENCIL_BITS` decides whether capping is available.
- **Snapshots:** they use a `DEPTH24_STENCIL8` attachment and cap exactly when the screen does.
- **Validation:** `SectionCapTest` runs a CPU reference of the same counting rule on closed, hollow, overlapping,
  separate, inside-out, glTF-transformed, open, non-manifold and flipped meshes, and checks the policy with the
  real mesh check. On the emulator, the GPU result matched the reference for the closed knot, the hollow box, the
  overlapping cubes, the transformed 3MF and the non-manifold fallback.
- **Cost:** the count pass sends the geometry through the GPU a second time.

  | Mesh | Emulator software GPU, per frame |
  |---|---|
  | 1M triangles, uncapped section | 52 ms median |
  | 1M triangles, capped section | 98 ms median |

  On hardware GPUs a second vertex pass is cheap, and the minimal program skips all fragment shading in the count
  pass. Normal viewing and uncapped sections are unaffected.

## Mesh diagnostics

`app.strata3d.diagnostics` is pure JVM and inspection-only: it reads `Mesh.vertices` and never writes to them. It
has no editing framework, repair engine or plugins.

- **Analysis** (`MeshAnalyzer`):
  1. Weld corners on a grid with 1e-6 × the bounding-box diagonal, using an open-addressing hash.
  2. Find degenerate triangles: collapsed after welding, or zero area relative to their edge lengths.
  3. Count the uses of each undirected edge through a hash. One use is a boundary edge. More than two is
     non-manifold. Two uses in the same direction is inconsistent winding.
  4. Union-find finds the shells. A second union-find with parity records each triangle's winding relative to its
     shell.
  5. Compute signed volume only for closed, orientable shells. On those, the volume sign decides which triangles
     are flipped, and the finding is *confirmed*. On open shells, the smaller winding group is reported as only
     *uncertain*. A closed shell with negative volume is inside-out, unless it lies inside another shell; then it
     is reported as a probable cavity (info).
- **Findings** (`DiagnosticsReport.findings`): each has a type, severity, certainty, count, explanation and
  references to the original geometry:
  - triangle `t` means floats `9t..9t+8` of the render buffer;
  - edge `3t+k` means triangle `t`, from corner `k` to corner `k+1`.

  `TopologyMap` keeps the corner → welded vertex mapping and the triangle → shell mapping. Corners duplicated for
  rendering (soups, UV seams, hard normals) map to the same vertex. Future repairs can therefore act on a report
  without re-running the analysis.
- **Versioning:** a report carries the `MeshFingerprint` it analyzed (triangle count + 64-bit hash of every
  coordinate). It is only valid for geometry with the same fingerprint.
- **App wiring** (`ViewerViewModel`):
  - It runs only when the user taps Analyze, on `Dispatchers.Default`, and polls cancellation between chunks.
  - An LRU cache keeps the last 2 reports by fingerprint.
  - Results apply only if the same mesh is still shown.
  - An out-of-memory error is reported, not fatal.
  - Loading is unchanged: nothing is computed until requested.
- **Highlights** (`DiagnosticOverlay`, renderer): edges and flipped triangles are copied from the original buffer
  into one extra VBO, capped at 250,000 per kind.
  - Lines draw twice, like the measurement: faint through the model, then solid where visible.
  - Flipped triangles use polygon offset.
  - Highlights are never included in snapshots.
  - While sectioning, they use a `SECTION` variant of the line shader, so the cut-away half's problems are hidden.
    Measurement guides are not clipped: they join points the user picked.
- **Cost** (sampled heap growth includes garbage; the report keeps about 16 bytes per triangle):

  | Where | Triangles | Time | Peak heap growth |
  |---|---|---|---|
  | JVM | 1M | ~0.2–0.3 s | — |
  | Emulator, x86 host | 102k | 0.42 s (first run, while the JIT warms up) | +10 MB |
  | Emulator, x86 host | 199k | 0.33 s | +14 MB |
  | Emulator, x86 host | 300k | 0.53 s | +22 MB |
  | Emulator, x86 host | 500k | 1.0 s | +63 MB |
  | Emulator, x86 host | 1M | 1.9 s | +80 MB |

  Phone numbers: see TESTING.md, "Benchmarking the mesh check on a phone".

## Snapshots

`SnapshotSheet` (UI) asks `ModelSurfaceView.captureSnapshot` for an off-screen render.
`ModelRenderer.renderSnapshot` then runs on the GL thread:

1. Checks the requested size against `GpuLimits` (`GL_MAX_RENDERBUFFER_SIZE`, `GL_MAX_VIEWPORT_DIMS`). A size that
   doesn't fit is refused, never shrunk.
2. Renders into a 4× multisampled framebuffer of exactly the output size, using
   `OrbitCamera.computeSnapshotMatrices`. That is the on-screen projection without the lens shift, scaled so the
   unobstructed rectangle fills the image. The visible surface is not touched.
3. In transparent mode, clears to alpha 0, skips the grid, and blends with premultiplied alpha. Measurement
   guides are never drawn.
4. Draws the framed-mode grid as CPU-built screen-space quads (`buildGridQuads`), not GL lines. Line rasterisation
   into multisampled off-screen buffers is unreliable on some GPUs, and wide lines are rarely supported.
5. Resolves, reads back, and deletes every GL object in a `finally` block.

`snapshot/SnapshotPlanning` holds the pure sizing and formatting rules (unit-tested). `SnapshotExporter` builds
the bitmap and caption, writes the PNG to `cacheDir/snapshots`, shares it through `FileProvider` with a temporary
read grant, or copies it to a user-chosen document. Temporary files older than 15 minutes are deleted.

## Recent Files

The `app.strata3d.recent` package is self-contained and deliberately separate from any future project or scan data.

- `RecentFile` is the record: name, document URI, companion URIs, format, size, triangle count, time, whether
  access is persisted, and the thumbnail file. `RecentFiles` holds the pure list rules: newest first,
  deduplicated by document, at most 15 entries, and which URIs become releasable. `RecentFilesCodec` is the
  versioned JSON. All three are unit-tested (`RecentFilesTest`).
- `RecentFilesStore` keeps `files/recent/recent-files.json` (an `AtomicFile`) and `files/recent/thumbnails/`. All
  of its work runs on `Dispatchers.IO` behind a mutex. After a successful load it calls
  `takePersistableUriPermission` and records whether Android granted it. An entry is **available** only when every
  URI still has a persisted grant; otherwise the UI offers **Reopen**. A failed open marks the entry unavailable.
  Removing or trimming entries releases grants that no other entry uses, and never touches the user's files.
- A file that fails to parse is renamed `recent-files.corrupt.json`, and the history starts fresh. A newer schema
  version is left untouched (read-only).
- Thumbnails come from the snapshot renderer: a transparent render about 256 px across, made after the first
  frame, then trimmed to the model. They are decoded lazily for visible rows and kept in a small LRU cache.
- Future fields such as pinned entries, collections and notes belong in a new schema version (`version: 2`), with
  defaults for older files.

Store calls are made *before* `MutableStateFlow.update`. `update` may re-run its block when the state changes
concurrently, so it must never contain side effects.

## Guided capture

**The phone's abilities (`DeviceProfile`).** The app reads what the phone offers once, then derives its decisions from
those facts in plain, unit-tested functions. Capture, reconstruction and the device report (Captures › menu › Device
report) all apply the same rules; nothing is tuned to one model of phone.

| Fact | Decision |
|---|---|
| Android's low-RAM flag, or under about 2.8 GB of RAM | Low-memory phone: photos up to 8 MP (else 12 MP), JPEG 90 (else 92), at most one smart-shutter photo every 700 ms (else 400 ms) |
| Rotation-vector and gyroscope sensors | Smart shutter available; without both, capture is manual only |
| 64-bit ARM (`arm64-v8a`) | Building models available; other processors can capture, and the build reports `unsupported_device` |
| CPU cores, free memory now | Reconstruction threads (half the cores, 1–4) and the SfM feature setting (see On-device reconstruction) |

The device report lists the facts, then what the app does on this phone.


Capture produces a dataset for on-device reconstruction ([CAPTURE_FORMAT.md](CAPTURE_FORMAT.md), version 2):
full-resolution photos, each with the phone's orientation from its motion sensors (so gravity is known per photo), the
image rotation and lens intrinsics, plus the user's tap on the object. There are **no camera poses**: structure from
motion computes them from the photos. The ARCore capture was removed before release: on the A17 its tracking diverged while still
reporting "tracking", and the reconstruction spike showed SfM registering every photo of a matte object.

**Camera stack (`CameraXCapture`).** CameraX binds two use cases to the activity lifecycle: a `Preview` and an
`ImageCapture`. Both use 4:3, the sensor's full field of view.
- The photo is the largest 4:3 size up to about 12.6 MP (8 MP on phones under 2.8 GB of RAM), in
  `CAPTURE_MODE_MINIMIZE_LATENCY`, written by CameraX straight to `images/NNNNNN.jpg` with its EXIF orientation.
- The preview uses `FIT_CENTER`, so the whole photo is visible and a tap maps exactly onto it (`CameraGeometry`).
- Intrinsics come from Camera2 characteristics (`CameraGeometry.intrinsics`):
  - the published calibration where there is one (`camera2`);
  - otherwise focal length over pixel pitch (`focal_length`; 3125 px for the A17's 4080-px photos, against SfM's
    self-calibrated 3147 px);
  - otherwise an explicit `assumed` 66 degree field of view.

**Shutter (`SmartShutter`, pure Kotlin).** Smart (the default) or manual, set in the Captures menu and switchable
per capture. A smart photo fires when all of these hold:
- the object is marked;
- no photo is in flight;
- the camera's viewing direction is at least 9 degrees from every earlier photo;
- the phone has been steady (under 0.2 rad/s) for 150 ms;
- 400 ms have passed since the last photo (700 ms on low-memory phones).

Twisting the phone about the lens, or walking back to a covered spot, does not count. When the ring fills, the phone gives one short buzz (the cue to pause): a plain 60 ms vibration marked as touch feedback, so it follows the system touch-vibration setting. (A haptic *effect* was used first; phones whose vibration motor has no effect support, like the Galaxy A17, dropped it silently.) On the A17 test, gaps of 20-26 degrees came from walking on after the ring filled. **One photo in flight:** the
manual button goes through the same gate, so the camera never gets a second request while one is being saved. A
failed photo pauses the smart shutter; it never retries on its own. The shutter only decides *when*; both modes call
the same `takePhoto`, and it never changes camera settings.

**Motion sensors (`MotionSensors`).** The game rotation vector (gyroscope + accelerometer, no compass) and the
gyroscope run on their own `HandlerThread`, registered only while the screen is resumed. A phone without them gets
manual capture, with a message saying so.

**Coverage (`CoverageRings`).** Two rings of 24 cells by the camera's azimuth, relative to the first photo: lower
(tilted down less than 35 degrees) and upper (looking down from above). The heading can drift by a few degrees over a
long capture; the dial is guidance, not a measurement.

**Threads:**

| Thread | Work |
|---|---|
| Main | CameraX binding, taps, buttons, `takePicture` calls |
| Sensors (`strata-sensors`) | Orientation and gyroscope readings, smart-shutter decisions |
| IO (`strata-capture-io`) | CameraX photo callbacks, EXIF, frame lines and the manifest, one at a time |

Shared capture state is guarded by one lock. Closing the screen unbinds the camera: a photo still in flight is then
reported as failed on the IO thread, the capture is closed (kept, or deleted if it has no photos), and only then does
the IO thread stop.

**Lifecycle.** Pausing the app keeps the capture open (CameraX closes and reopens the camera; sensors stop and
restart). There is no world frame to lose. A capture still marked `recording` when the app was killed is repaired to
`interrupted` the next time the library lists it.

**Budgets.**
- Storage: a capture needs 500 MB free to start, and recording stops below 200 MB.
- Heat: thermal status `SEVERE` pauses the smart shutter.

## On-device reconstruction

**Build model** on a capture's summary turns its photos into a textured model on the phone. The pipeline's programs and
how to build them are in [`tools/recon`](../tools/recon/README.md).

**Tools.** The pipeline programs are built by `tools/recon/build.sh`: MVE's `makescene`, `sfmrecon` and
`meshclean`, mvs-texturing's `texrecon`, and Strata:3D's own `objbox` and `groundcut`. `openmvs/build_openmvs.sh`
adds OpenMVS's `InterfaceCOLMAP`, `DensifyPointCloud` and `ReconstructMesh`, packaged as `omvs_import`,
`omvs_densify` and `omvs_mesh`.
- They are static arm64 executables. The C++ runtime and OpenMP are linked in, so they need only Android's system
  libraries. The Gradle task `prepareReconTools` packages them as `lib/arm64-v8a/libmve_<tool>.so`.
- With legacy jniLibs packaging, Android extracts them to the app's native-library folder, the one place an app may run
  its own programs from.
- A build fails with a clear message if the tools haven't been built.
- On other processors `ReconRunner` reports `unsupported_device`; the app itself still installs.

**Process model.**
- `ReconService` is a foreground service (`mediaProcessing` on Android 15+, `dataSync` before) in its own process,
  `:recon`, so a tool that runs out of memory can never take the app down. It holds a partial wake lock for the job and
  runs one capture at a time.
- `ReconRunner` runs each stage as a child process: background priority (inherited from its thread), OpenMP threads =
  half the cores (1–4), and output to `files/recon/<id>/logs/<stage>.log`.
- Cancel destroys the running child at once.

| Stage | Tool | Notes |
|---|---|---|
| Preparing photos | makescene | Imports at about 3 MP |
| Placing the photos | sfmrecon | Exhaustive matching; 3 MP features only if they fit the free memory measured now (200 MB + 17 MB/photo, 700 MB left), else 0.8 MP; the 600 MB guard retries at standard if needed. Under 60 % placed: re-solve from the saved matching, first single-threaded (deterministic), then up to 3 more times; under 30 % (or 10 photos): stop with `too_few_photos_placed` |
| Finding the object | objbox | Gravity + surface + tap; rotates the scene upright. Exit 2 = no object, exit 3 = tap outside |
| Measuring depth | omvs_import, omvs_densify | `ColmapExport` writes the placed views and SfM points as a COLMAP model with `images/` links to the undistorted photos, and the box as OpenMVS's region of interest. OpenMVS runs in the working folder (it resolves image paths against it): photos scaled down 4×, never below 500 px on the short side; depth maps fused into a dense cloud inside the box |
| Building the point cloud | (check) | The dense cloud must hold at least 1,000 points |
| Building the surface | omvs_mesh | Delaunay graph-cut surface, reduced to about 100,000 faces when larger |
| Removing the surface underneath | groundcut | Only when a surface was found: low, up-facing faces below 2 % of the camera distance |
| Cleaning up / Adding colour | meshclean: removes only stray pieces under 2 % of the vertices (`-t0`, no confidence values; `-n`, no thin-triangle collapsing), then texrecon | The model is written to `<capture>/model.tmp` and renamed to `model/` only when complete |

**Box review.** With "Review the box before building" on, `ReconRunner` stops after `objbox` with state `review`,
keeping the scene, and the service exits: nothing runs while the user decides. A notification opens the capture.
- The review screen (`BoxReviewScreen`, geometry in the pure `BoxReview`) draws the box on the scene's
  `undistorted.png` photos. These are the ones whose MVE cameras are exact pinhole models, in the aligned Z-up frame.
- It uses three photos picked about 120 degrees apart around the box axis, each rotated upright by the frame's
  `rotationDegrees`.
- Dragging a handle changes the top, the bottom or the radius by the least-squares match between the finger's motion
  and the handle's on-screen motion for that parameter.
- **Build model** writes the box atomically to `scene/objbox.txt` and starts the service in resume mode, which
  continues from the depth stage. `objbox_auto.txt` keeps the app's box for **Reset**.
- **Cancel** while paused marks the build cancelled and deletes the scene from the app's own process.
- Only an `objbox` v2 box (upright, centred) can be reviewed. A legacy box builds without pausing.

**Real size.** Photos alone don't give a model a real size. The user can give one measurement, the object's height or
its width, in cm or in: from the **Real size** row of the box review, or from the Model ready card (**Add size** /
**Edit**) at any time later.
- It is one number on purpose: a second one could disagree with the first, and choosing between them would be a
  silent guess. The other two dimensions follow from the model.
- The request is stored with the capture (`size.json`, `SizeStore`), so every build of that capture is sized the same
  way. `ReconRunner` applies it to the new model before moving it into place. Editing it later rescales the existing
  model at once.
- Measuring (`ModelSize`, pure and unit-tested) works on the Z-up model. Height is the extent along Z. Width and depth
  are the longer and shorter sides of the smallest rectangle around the footprint seen from above, so a turned box
  gives its sides, not its diagonal. Each extent ignores the outermost 0.5 % of vertices at both ends, so stray
  fragments don't count. The viewer's Model info uses the full bounding box, so it can read slightly larger.
- The scaled OBJ is in millimetres. Its first line, `# Strata:3D length unit: mm`, makes `ObjParser` report
  millimetres ("from file"), so the viewer's measurements are in real units. Plain OBJ files are unaffected.
- Accepted values are 5 mm to 5 m. Without a size the model stays unitless and the card says "Size not set".
- If sizing fails at the end of a build, the build still succeeds, the model stays unsized, and the card offers
  **Add size**. A failure when editing shows a message and leaves the model as it was.

**Safety.** Every stage is stopped if free memory drops below 350 MB (`low_memory`): the phone comes first. Storage
needed (500 MB + 15 MB per photo) is checked before starting. The working scene is deleted at the end; only
`status.json` and the logs stay.

**Status.** `files/recon/<id>/status.json` is written atomically on every change (`ReconStatus`): state, stage,
depth batch, SfM setting and reason, box method, photos placed, model size, and on failure a reason code plus
technical detail.
- The summary screen polls it: every second while building, otherwise every few seconds.
- A "running" status without a running `ReconService` is shown as interrupted. It checks the service, not the
  process, because Android keeps the `:recon` process cached after the service stops.
- The notification shows the same step.

## Theme and preferences

`MainActivity` reads the saved theme from `ThemePreferences` (SharedPreferences) and calls `setTheme` before
`super.onCreate`, so the very first frame already uses the right colours. After that, Compose state drives
`StrataTheme`, the status/navigation bar icon colours, and the window background.

## Adding a file format

1. Add a parser in `model/` that returns a `Mesh` through `MeshBuilder`, and keep it free of Android imports.
2. Register it in `ModelParser`: the `Format` enum, extension dispatch, `MODEL_EXTENSIONS` and, if possible, `sniffFormat`.
3. Add its MIME types and `pathPattern`s to `AndroidManifest.xml` (VIEW, SEND and SEND_MULTIPLE filters).
4. Add unit tests, and cases in `tools/generate_test_models.py` with expected results computed independently.
5. Update `docs/FORMATS.md`, `README.md` and `CHANGELOG.md`.
