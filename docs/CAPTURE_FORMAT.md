# Capture dataset format (`strata3d-capture`, versions 1 and 2)

Strata:3D's capture mode writes each capture to its own folder in the app's private storage
(`files/captures/<id>/`). The same layout is inside an exported `.zip`, along with files for common
reconstruction tools. The code lives in `app/src/main/java/app/strata3d/capture/format/`.

**Versions:**
- **Version 2** (current) comes from the CameraX capture. There are **no camera poses**: reconstruction computes them
  from the photos. Each photo carries the phone's orientation from its motion sensors, and the capture carries the
  user's tap on the object.
- **Version 1** came from the earlier ARCore capture and is no longer written. Readers still accept it; every photo
  had an ARCore pose.

```
manifest.json      capture metadata, replaced atomically whenever it changes
frames.jsonl       one JSON object per photo, appended and synced as each photo lands
images/000001.jpg  full-resolution JPEG as the camera stored it, no location metadata
depth/000001.d16   version 1 only: ARCore raw depth (uint16 millimetres, little-endian)
depth/000001.c8    version 1 only: ARCore raw-depth confidence (uint8, 0 = none … 255 = high)
```

An exported `.zip` also has:
- `colmap/cameras.txt`, `colmap/images.txt` and an empty `colmap/points3D.txt` (version 2: no image poses, so COLMAP
  registers the photos itself);
- `transforms.json` (nerfstudio; version 1 only, since it needs poses);
- `README.txt`.

## Conventions

### Both versions

- **Intrinsics:** pinhole fx, fy, cx, cy in pixels for the image **as stored** (width × height); pixel (0, 0) is the
  centre of the top-left pixel. `manifest.camera.intrinsicsSource` says where they came from:
  - `focal_length` (version 2): the lens focal length over the physical sensor size, principal point at the centre;
  - `assumed` (version 2): the camera published no lens data, so a 66° horizontal field of view was assumed;
    reconstruction must self-calibrate;
  - `camera2`: the phone's published calibration;
  - `arcore_scaled` (version 1): ARCore's tracking-image intrinsics scaled to the photo size.

  No distortion model is stored; reconstruction refines intrinsics.

### Version 2

- **Images** are stored as CameraX writes them. `rotationDegrees` on each frame (0, 90, 180 or 270, clockwise) shows
  the image upright, and matches the JPEG's EXIF orientation.
- **Orientation:** `orientation` = [x, y, z, w] is a unit quaternion rotating **device** coordinates into the world
  frame of Android's game rotation vector sensor.
  - World +z is **up** (against gravity). The heading is relative and drifts slowly (no compass).
  - Device axes: +x right, +y up along the screen, +z out of the screen. The back camera looks along −z.
  - So the gravity direction in device coordinates is the conjugate rotation applied to (0, 0, −1); see
    `SensorOrientation.downInDevice()`.
  - It is sampled when the shutter fires; absent if the phone has no rotation sensor.
- **Object tap:** `manifest.objectTap` = {`u`, `v`, `timestampNs`, `orientation`}.
  - (`u`, `v`) is the tap in 0–1 across the **upright** camera image as shown on screen (origin top-left).
  - `orientation` is the phone's orientation at the moment of the tap.
  - The tap comes before the first photo; reconstruction uses it to pick out the object.

### Version 1

- **Units:** metres, in the **object frame**: the frame of the ARCore anchor placed on the object. Its origin is the
  bubble's base on the supporting surface (or the two-view point), with +y up (gravity-aligned).
- **Pose:** the frame's **camera-to-object** transform, as `t` = [x, y, z] and `q` = [x, y, z, w]. The camera axes are
  ARCore's (OpenGL): +x right and +y up in the sensor image, looking down −z.
  - COLMAP export: converted to world-to-camera in the OpenCV convention (+y down, +z forward).
  - nerfstudio export: kept as camera-to-world with OpenGL axes.
- **Images** are stored in sensor orientation (landscape on phones) and match the pose and intrinsics exactly.
- **Timestamps** are camera sensor timestamps (ns) on the same clock as ARCore's frames.

## `manifest.json`

| Field | Meaning |
|---|---|
| `format`, `version` | `"strata3d-capture"`, `2` (or `1`). Readers reject newer versions. |
| `id`, `name` | Folder id (creation time); user-visible name (may be empty) |
| `createdAt`, `updatedAt` | Unix time (ms) |
| `appVersion`, `device` | Provenance |
| `camera` | Camera id, photo size, focal length and sensor size (mm), `intrinsicsSource` |
| `orientationConvention` | Version 2: a one-line description of `orientation` |
| `objectTap` | Version 2: the tap on the object (see above) |
| `arcoreVersion`, `poseConvention`, `target` | Version 1 only. `target` = the ARCore bubble: `center`, `radius` (m), `placement` `surface` (with `base`) or `two_view` |
| `frameCount`, `coverage` | Photos written; fraction (0–1) of coverage cells with a photo (version 2: the two rings averaged) |
| `state` | `recording`, `complete`, or `interrupted` (the app stopped mid-capture; the photos written so far are kept) |

## `frames.jsonl` (one object per line)

| Field | Meaning |
|---|---|
| `index`, `image` | 1-based photo number and relative JPEG path |
| `timestampNs` | Version 2: when the shutter fired (sensor clock, `SystemClock.elapsedRealtimeNanos`); version 1: camera sensor timestamp |
| `orientation`, `rotationDegrees` | Version 2 (see above) |
| `pose`, `tracking`, `depth` | Version 1 only |
| `intrinsics` | `{fx, fy, cx, cy, width, height}` |
| `exposureNs`, `iso`, `focusDiopters` | Camera settings for the photo, when known |
| `sharpness` | Variance of the Laplacian (higher is sharper; compare within a capture); 0 = not measured |
| `manual` | `true` when the photo was taken with the manual button rather than the smart shutter |

Readers must skip a damaged line (the app may have stopped mid-write) and frames whose image file is missing.
