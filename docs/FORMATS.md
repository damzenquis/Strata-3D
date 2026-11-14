# Supported formats

Strata:3D renders geometry only: every model is drawn in a single colour, so materials, textures, vertex colours
and normals stored in the file are read past and ignored.

| Format | Extensions | Supported | Not supported |
|---|---|---|---|
| **STL** | `.stl` | Binary and ASCII; binary files whose header starts with `solid`; truncated binary files (whatever is complete is shown) | Colour extensions in the attribute bytes |
| **OBJ** | `.obj` | `v`/`f` with `v`, `v/vt`, `v//vn`, `v/vt/vn`; triangles, quads and convex n-gons; negative (relative) indices; line continuations; `o`/`g`/`s`/`usemtl`/`mtllib` are skipped | Free-form curves and surfaces |
| **PLY** | `.ply` | ASCII, binary little-endian, binary big-endian; all scalar types; any property order; extra elements; n-gon faces | `tristrips` elements |
| **3MF** | `.3mf` | Mesh objects; build-item transforms; `<components>` (multi-part objects, nested); Production extension parts referenced by `p:path` (Bambu Studio, Orca Slicer); any ZIP entry order | Beam lattices, slices, volumetric data |
| **glTF 2.0** | `.gltf`, `.glb` | Scenes, node hierarchy (TRS and matrix); triangles, strips and fans; 8/16/32-bit indices; interleaved buffers; sparse accessors; embedded `data:` buffers; separate `.bin` files (see below) | Morph targets and skinning (the rest pose is shown); points and lines are skipped |
| **glTF compression** | | `KHR_draco_mesh_compression`, `EXT_meshopt_compression`, `KHR_meshopt_compression` (vertex codec v0 and v1, all filters), `KHR_mesh_quantization` | |
| **ZIP packages** | `.zip` | A model plus the files it references, at any depth; macOS `__MACOSX` entries are ignored | Archives over 1 GB unpacked |

## glTF files with separate `.bin` files

Android gives an app access to one file at a time, so a `.gltf` that points at a separate `scene.bin` needs that
file provided too. There are three ways:

1. **Pick them together.** In the file picker, long-press the `.gltf`, tap its `.bin` (and textures, if any), then tap **Select**.
2. **Share them together** from a file manager to Strata:3D.
3. **Open a `.zip`** that contains the `.gltf` and its files.

If only the `.gltf` is opened, Strata:3D names the missing file and explains these options.

## Units

Every length, area and volume Strata:3D shows is converted to **millimetres** (metres from 10 m upwards; areas in
cm², volumes in cm³). The unit of the file's coordinates comes from, in order:

1. **Your choice** in the measurement card (Units chip), remembered per file in Recent Files. Use it when a file
   declares the wrong unit, or to give an STL/OBJ/PLY its real scale.
2. **The file:** glTF/GLB is always metres (glTF 2.0 specification); 3MF declares its unit in the `unit` attribute
   (micron, millimeter, centimeter, inch, foot or meter; millimetres when absent).
3. **Assumed millimetres** for STL, OBJ and PLY, which store no unit. The app labels these values "assumed", and
   snapshot captions leave the unit out.

The unit only changes how coordinates are interpreted; geometry is never rescaled. Transforms in the file (3MF build
items and components, glTF node hierarchies) are applied while loading, so measurements are taken on the assembled
model. Volume is only meaningful for closed (watertight) meshes.
