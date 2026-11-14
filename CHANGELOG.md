# Changelog

All notable changes to Strata:3D are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Each release also has a short, user-facing
summary in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` for app stores.

## [Unreleased]

## [0.9.0] - 2026-11-14

First public release.

### Added
- **Settings: Support, Help, About, Credits and License.** Each opens a full-screen page. License shows the AGPL notice,
  the full licence text and the third-party notices.
- **Photo colours in the viewer:** models that come with colour images (OBJ with materials, such as the models the app
  builds) show in their photo colours. A **Photo** button on the right, below Fit, switches between photo colours and
  the plain model colour. Solid, edges and wireframe work in both.
- **Model ready card:** a picture of the built model, **Export model** (GLB with the colours inside, or OBJ in a .zip;
  Save or Share; progress with Cancel), and the "Review the box before building" switch next to Build again. Sized
  models export at real size (GLB in metres).
- **Real size for built models:** enter one measurement, the object's height or width, in cm or in. Do it on the box
  review or later from the Model ready card. The model is scaled to millimetres, the card shows height × width ×
  depth, and the viewer measures in real units. Without a size, the card says "Size not set".

- **Build a 3D model on the phone.** A capture's summary has **Build model**, which runs the whole reconstruction on
  the phone, with no internet: placing the photos, finding the object, depth, surface, removing the surface underneath,
  and colour.
  - It runs in the background, in its own process, with a notification. You can leave the app; **Cancel** stops it at
    once and keeps the photos.
  - The summary shows the current step and the time so far. When it's done, it shows how many photos were used,
    explains any gaps, and offers **Open in viewer**. If it can't build, it gives a plain reason (low memory or
    storage, too few photos placed, the tap missed the object) and **Try again**.
  - It adapts to the phone. The detail used for placing photos depends on the memory free at that moment. Building
    stops if the phone runs low on memory, rather than slowing the phone down.
  - If too few photos are placed, it re-solves, which takes seconds because the matching is reused. If too few are
    still placed, it stops and says so rather than building from a bad start.
  - On a Galaxy A17: a 58-photo capture takes about 7 minutes.
  - The pipeline (MVE, mvs-texturing, both BSD-3-Clause) ships as native programs for arm64 phones, adding about
    5 MB to the app.
  - The model opens in the viewer as geometry, standing upright; colour in the viewer comes in a later update.
  - **Review the box before building** (off by default; off every time you open a capture).
    - Building pauses after placing the photos, with nothing running in the background, and the notification says so.
    - **Check the box** shows the box the app found, drawn on three of your photos from different sides. Drag the top,
      the bottom or a side to change it, switch photos to check the other sides, and **Reset** to go back to the
      app's box.
    - **Build model** continues from there with your box. **Cancel** ends the paused build and frees its working files.
- Third-party licence notices (MVE, mvs-texturing, mapMAP, rayint, oneTBB, Eigen, libjpeg-turbo, libpng, zlib,
  Openize.Drako) are included in the app as `assets/THIRD_PARTY_NOTICES.txt`.
- **Guided capture.** Open it from **Capture an object** on the start screen, or **Captures** in the settings panel.
  - **Tap the object** you're capturing to start; the shutter stays locked until you do. Retap to change it before
    the first photo.
  - **Smart shutter (default).** Walk slowly around the object and pause briefly when the phone buzzes: a photo is taken each time the
    camera has turned about 9° from every earlier photo and the phone is steady. Twisting the phone or walking back
    to a covered spot doesn't take duplicates. A ring around the shutter fills toward the next photo, and the screen
    says "Keep moving around" or "Hold steady". The shutter pauses and resumes the smart shutter.
  - **Manual shutter.** Choose it under **Shutter** in the Captures menu, or switch with the Smart/Manual chip for
    one capture.
  - **One photo at a time.** The shutter greys out ("Saving photo…") until the last photo is safely written, in
    either mode; the camera never gets a second request. A photo that fails to save pauses the smart shutter and
    says so; it never retries on its own.
  - A two-ring coverage dial (low angles and from above) shows what is still missing. The photo count reads
    "N · 40 or more".
  - Full-resolution 4:3 photos (up to about 12.6 MP; 8 MP on phones under 2.8 GB of RAM) through CameraX. The
    preview shows the whole photo.
  - Each photo stores the phone's orientation from its motion sensors (so "down" is known for every photo), its
    rotation, and lens intrinsics from the camera's focal length and sensor size. No positions are recorded:
    reconstruction computes them from the photos.
  - The Captures library lists, renames, deletes and exports captures as `.zip` files with COLMAP camera files.
  - The camera permission is requested only when capture starts. Strata:3D still has no internet permission.
  - Capture is portrait only. Pausing the app keeps the capture open.
  - A phone without a gyroscope captures manually, and says so.
  - Heat (thermal status severe) pauses the smart shutter; low storage stops the capture and keeps the photos.
- Capture dataset format (`strata3d-capture`, version 2; see `docs/CAPTURE_FORMAT.md`):
  - a crash-safe layout (atomic manifest, appended frame log);
  - per-photo phone orientation and image rotation;
  - the object tap;
  - lens intrinsics marked as published (`camera2`), derived from the focal length (`focal_length`), or `assumed`.
- **Device report** (Captures menu): motion sensors, RAM, and each back camera's lens data and photo sizes.

### Changed
- **One place reads the phone's abilities** (`DeviceProfile`): capture's photo size, JPEG quality and shutter pace,
  the smart shutter, building models, and threads all follow from the same unit-tested rules. The device report
  lists the facts, then what the app does on this phone.
- **Reconstruction: OpenMVS builds the dense cloud and the surface** (previously MVE's dmrecon, scene2pset and
  fssrecon). On the Galaxy A17, surfaces are far more complete (the statue's wings and the bottle's neck are no longer
  shredded) at about the same memory, and faster. Areas the photos show little detail on, like plain backs or clear
  glass, can come out as flat patches. Texturing is unchanged (texrecon). OpenMVS (AGPL-3.0) and its libraries are
  listed in the third-party notices.
- The capture summary's storage tile is labelled "Storage" (it was "Size", which now means the real size).
- **Licence:** Strata:3D is now licensed under the GNU AGPL, version 3 or later (previously Apache-2.0). The code from
  the original 3D Model Viewer keeps its Apache-2.0 licence, whose text now ships in `third_party/`.
- **Reconstruction tools:** all nine tools, texturing included, are now built from one MVE version (upstream
  rev bf2279f); the separate older MVE copy used only for texturing is gone. Results are unchanged.

- The launch screen (Android 12 and newer) always uses the light background, also in dark mode, so the black and
  cyan logo is visible. The app itself still follows the chosen theme.

### Fixed
- **The capture's "hold steady" buzz now works on every phone.** It used a haptic effect that phones with a basic
  vibration motor (the Galaxy A17 among them) silently drop; it is now a plain short vibration (new VIBRATE
  permission, granted without a prompt).
- **The coverage dial and the smart shutter now agree.** The dial marked a spot missing while the shutter, which
  wants 9° from every photo, could barely take one there, so going back to fill it stalled the ring. A dial cell now
  shows as missing exactly where the shutter can take a photo.
- **glTF and GLB models stand upright** (they are +Y up; the viewer is +Z up). Models exported from the app reopen
  upright too.
- **Files that aren't 3D models are refused.** An .xml file was opened as a binary STL (the one format without a
  signature), giving nonsense triangles and mesh-check results. Now:
  - files are refused by name when their extension isn't a model format;
  - without an extension, data is read as STL only when it really looks like one (not text, size matching its
    triangle count);
  - coordinates no real model has (over a trillion units) are refused.

  Such files no longer appear in Recent models.
- The capture summary scrolls, so the whole Model ready card is reachable.
- Settings: Close model sits under "Download as new file", and the "new" badge on Captures is gone.
- Snackbar and share messages read their text in a configuration-aware way, so they follow a language change made
  while a screen is open. Android lint now reports no errors.

### Removed
- F-Droid packaging (build recipe and F-Droid-only build settings) inherited from the original project. Strata:3D
  is not distributed through F-Droid. The Fastlane store-listing folder stays, as it also serves the Play Store.

## [0.8.0] - 2026-11-08

### Added
- **Units for measurements and model info.** Values are converted to millimetres from the unit the file declares
  (glTF is metres, 3MF has a `unit` attribute). STL, OBJ and PLY store no unit, so millimetres are assumed and
  labelled "assumed". A **Units** chip in the measurement tool sets what the file's coordinates mean (µm, mm, cm, m,
  in, ft). Strata:3D remembers the choice for each file and applies it to measurements, Model info and snapshot
  captions.
- **Snap** chip in the measurement tool. With Snap on, a tap picks the nearest *visible* vertex within a
  finger-sized radius on screen. Vertices on the hidden side of the model or behind another part are never picked.
  With Snap off, or away from vertices, the tap picks the exact point on the surface. Snapped points show a filled
  marker; free points show a hollow one.

- **Section (cross-section clipping).** A Section button in the toolbar cuts the model with an X, Y or Z plane.
  The Section bar above the toolbar has:
  - axis chips;
  - a slider showing the plane's position in millimetres, in the same coordinates as measurements;
  - a button to show the other side;
  - a close button.

  By default the half facing the camera is removed, so you look into the cut. The model's data is never changed,
  and snapshots show the cut as on screen. Normal viewing keeps the original shader; the clipping variant is only
  used while sectioning.
- **Filled section cuts.** On devices with a stencil buffer, the cut face of a section is filled with a solid colour,
  so wall thickness and cavities are easy to see.
  - **Colour:** orange, or blue on an orange model.
  - **Behaviour:** hollow parts stay open, and overlapping parts fill as one.
  - **When:** only for models the mesh check has confirmed closed and consistently wound. Everything else shows the
    uncapped cut, because a fill would be wrong in places.
  - **Automatic check:** opening Section runs the mesh check in the background if it hasn't run yet, so the cut
    fills by itself. Closing Section cancels a check it started. A line in the Section bar says when the cut is
    being checked or why it isn't filled (not closed, unsupported device, Wireframe view).
  - **Snapshots** show the fill exactly as it appears on screen.
- **Section, measurement and mesh check work together.**
  - **Measuring with a section:** taps ignore the cut-away half, and snapping never offers a vertex there or one
    hidden behind a filled cut.
  - **The fill itself is never picked:** it shows the cut but isn't geometry, so a tap on it sets no point and
    no wall thickness is invented.
  - **Mesh-check highlights** follow the cut too: problems in the removed half are hidden.
- **Mesh check** in Model info. It runs on demand, in the background, and can be cancelled. It reports:
  - open (boundary) edges and how many open outlines they form;
  - non-manifold edges;
  - flipped triangles;
  - zero-area triangles;
  - non-orientable parts;
  - inside-out parts.

  Certain findings are kept apart from likely ones: a likely finding, such as which side is flipped on an open
  surface, is marked "possible". Hollow models whose inner shell faces inwards are noted as cavities, not defects.

  **Show problems in 3D** highlights open edges in orange, non-manifold edges in red and flipped triangles in
  magenta, with a legend; a dot on the Model info button means issues were found.

  The check never changes the model. Results are cached for the session and tied to the exact geometry they
  analyzed. On the emulator, a 1M-triangle model takes about 1.9 s.

### Changed
- Every accent now uses the Strata:3D cyan: switches, check marks and filled buttons use the brand cyan, and
  selected segments, the ruler and camera buttons, and the view rail highlight use a soft cyan. Accent text in
  light mode uses a deeper shade of the same cyan, so it stays readable on white.

### Fixed
- After the first measurement point, the instruction pill could cut off its Clear button next to the view rail. The
  instructions are shorter ("Tap the first point") and wrap when needed.
- glTF models were measured as if their coordinates were millimetres, so a 12 cm part read "0.12 mm". Model info
  had the same error.
- The test-model generator wound its torus-knot tubes inside-out (all knot fixtures, up to the 1M-triangle STL). Each
  quad is now reversed, so they check clean, and a deliberately inside-out torus
  (`stl/35_inside_out_torus_binary.stl`) was added as the test case for inverted models.
- Picking failed on very small triangles in metre-scale models (for example 0.1 mm details in a glTF), because the
  ray test used a fixed size threshold.
- Measurement markers had a minimum size of 0.5 units, which made them huge on models measured in metres.
- The measurement card's copy button was announced as "Copy path" by TalkBack.
- On narrow phones (360 dp wide), the Snapshot button was pushed off the bottom toolbar. The Solid, Edges and
  Wire modes now use a compact icon selector, so every control fits. Each mode button has an accessible label
  ("Solid view", "Solid with edges view", "Wireframe view") and reports its selected state to TalkBack.
- Opening a file that is not a 3D model (for example a PNG snapshot) no longer misreads it as STL. Images,
  videos, PDFs, archives and programs are recognised and rejected with a clear message.
- A model whose coordinates overflow (a damaged file) is rejected with a message instead of crashing the app;
  the build-plate grid is also bounded so no model can exhaust memory.

## [0.7.0] - 2026-11-07

### Added
- **Standard and orthographic views.** A collapsible view rail on the right edge has:
  - a perspective ↔ orthographic toggle that keeps the model's apparent size;
  - one-tap Top, Front, Side and Isometric views;
  - Fit, which frames the complete model bounds in the current direction.

  Camera moves animate over 250 ms, and any touch interrupts them. The rail remembers whether it is collapsed,
  separately for portrait and landscape.
- **Dedicated landscape layout:** a shorter app bar, the rail collapsed by default and arranged in two columns
  so every control fits, a measurement card with a maximum width, and info, panel and empty-state content that
  scrolls on short screens. Display cutouts and navigation bars are respected on every side.
- Framing (load, reset, presets, Fit) now keeps the model clear of the rail and bottom toolbar. The camera knows
  which areas they cover and re-centres the image in the remaining space.
- **Snapshot and Share.** A camera button in the bottom toolbar opens an export sheet with a live preview:
  - **Transparent:** a real alpha-channel PNG of the model only, with no background, grid or overlays.
  - **Framed:** the theme background, an optional grid, and an optional caption with the file name and size. Units
    are shown only when the file declares them (3MF, glTF).
  - **Sizes:** Screen, 2× and 4K (3840 px long edge), each showing its exact pixel size. Sizes beyond the GPU's
    limits are disabled; images are never shrunk.
  - Rendered off-screen without touching the visible viewer, framed exactly like the screen minus the controls,
    with memory checks before rendering.
  - **Share** through a temporary content-URI grant, or **Save** through the system document picker. No storage
    permission is needed, and temporary files are cleaned up.
- Meshes now record the length unit their file declares (3MF `unit`, glTF metres).
- **Recent Files.**
  - The empty viewer screen shows your last three models with thumbnails, and **View all** beside the heading
    opens the full list of up to 15. The gear panel also has **Recent files**.
  - Documents picked through the system picker reopen with no picker prompt, even after a restart. Read access is
    persisted only where Android actually grants it.
  - Files that arrived another way ("Open with", Share), or that were moved, deleted or had access revoked, show
    **Reopen**. Reopen starts the picker at the original location and replaces the old entry.
  - glTFs with separate `.bin` files remember their companion files; `.zip` packages and compressed models reopen too.
  - Thumbnails are captured in the background after a model loads, trimmed to the model, and cached. Recent Files
    never reloads a model to show them.
  - Remove individual entries or clear the history. Your files are never deleted; this app's access to them is
    released.
  - Stored in a versioned, private JSON file written atomically. A damaged file is set aside, and the app starts a
    fresh history.

### Changed
- The bottom toolbar's reset button has moved to the rail as **Fit**. Double-tap still resets to the default view,
  now with a smooth transition.
- Measuring works in orthographic views: tap rays travel along the view direction.

## [0.6.0] - 2026-11-07

First release as **Strata:3D**, an independent app forked from
[3D Model Viewer](https://github.com/nini1294/3d-model-viewer) 0.5.0 (Apache 2.0).

### Added
- **New formats**
  - PLY: ASCII, binary little-endian and binary big-endian; any vertex/face property layout; n-gon faces.
  - 3MF: mesh objects with build-item transforms, multi-part objects (`<components>`), and the Production
    extension's separate model parts (`p:path`) used by Bambu Studio and Orca Slicer.
  - glTF 2.0 / GLB: scene graph with TRS/matrix transforms, indexed and non-indexed triangles, strips and fans.
- **glTF compression and packaging**
  - Draco (`KHR_draco_mesh_compression`) via the Openize Drako decoder.
  - meshopt (`EXT_meshopt_compression` / `KHR_meshopt_compression`), including the v1 vertex codec and all filters.
  - Quantized geometry (`KHR_mesh_quantization`), sparse accessors, and accessors without a bufferView.
  - glTFs with separate `.bin` files: select the `.gltf` and its files together in the picker, share several
    files at once, or open a `.zip` package.
- **Interface**
  - Strata:3D branding: new name, logo in the top bar, and a new adaptive launcher icon with a themed (monochrome) variant.
  - Light/dark theme toggle (sun/moon) in the top bar. The choice is saved and used from the first frame on the next launch.
  - Settings side panel (gear button) with Open model, File location, Download as new file, grid toggle,
    model colour and Close model.
- **Quality**
  - A suite of 34 test models across all formats, from 2 triangles to 1,000,000 triangles. A generator script
    builds them with independently computed expected triangle counts, bounds and surface areas.
  - The meshopt decoder is checked byte-for-byte against meshoptimizer's reference decoder.

### Changed
- New application ID `app.strata3d` and a new release signing key. Strata:3D installs alongside 3D Model Viewer, not over it.
- The folder and the overflow (⋮) buttons are merged into the gear panel.
- Empty state: removed the decorative icon and centred the content. The "No model loaded" title now uses the
  theme's secondary text colour (it was unreadable on dark backgrounds). The Open button uses the brand cyan.
- The top bar is now solid, separate from the status bar, and the 3D viewport starts below it.
- Brand colour palette for light and dark themes, instead of wallpaper-based dynamic colour.
- Build toolchain: Gradle 9.9.0, Android Gradle Plugin 9.5.1 (built-in Kotlin), Kotlin 2.4.21, compile SDK 37.2,
  Compose BOM 2026.09.00.

### Fixed
- PLY: binary little-endian files with 16-bit values (`short`/`ushort` coordinates, indices or list counts) were
  read with the wrong byte order and rejected.
- 3MF: files from slicers that use `<components>` failed with "does not contain any buildable triangles".

## Inherited from 3D Model Viewer

The releases below were made by the original project, before the fork.

### [0.5.0]
- Interactive 3D measurement tool with vertex snapping, distance and ΔX/ΔY/ΔZ, copy to clipboard.

### [0.4.0]
- File location inspection and "Download as new file".

### [0.3.0]
- F-Droid metadata and Fastlane packaging; UI refinements.

### [0.2.0]
- Wavefront OBJ support (triangles, quads, convex n-gons, negative indices).

### [0.1.0]
- Initial release: binary and ASCII STL, OpenGL ES 3.0 renderer with Solid, Edges and Wireframe modes, model metrics.

[Unreleased]: https://github.com/damzenquis/Strata-3D/compare/v0.9.0...HEAD
[0.9.0]: https://github.com/damzenquis/Strata-3D/releases/tag/v0.9.0
