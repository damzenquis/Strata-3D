"""Generates the Strata:3D test-model suite: every supported format, from a 4-triangle tetrahedron up to
million-triangle stress meshes, plus edge cases real-world exporters produce.

Usage:  python tools/generate_test_models.py [output_dir]      (default: test-models/)

For every file, the expected triangle count and bounding box are computed here, independently of the
app's parsers, and written to expected.json. app/src/test/.../TestModelsTest.kt parses every file with the
app's real ModelParser and compares the result.

Tiers in expected.json:
  core        - what the format support promises (STL/OBJ baseline + PLY, 3MF, glTF/GLB basics).
  real-world  - structures common in files from real tools (slicers, scanners, exporters).
  limitation  - known out-of-scope features; the app must fail with a clear error, not crash.

Draco / quantized / meshopt samples are produced with the glTF-Transform CLI (npm i @gltf-transform/cli); point
the GLTF_TRANSFORM environment variable at it, and MESHOPTIMIZER_DIR at the meshoptimizer npm package it installs
(used by tools/meshopt_encode.mjs for the hand-built meshopt cases). Without them those files are skipped.
"""
import base64
import io
import json
import math
import struct
import sys
import zipfile
from pathlib import Path

import numpy as np

OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent / "test-models"
ENTRIES = []


# --------------------------------------------------------------------------------------------------
# Geometry (vertices: (N,3) float64, faces: list of index tuples, any polygon size)
# --------------------------------------------------------------------------------------------------

def box(sx=20.0, sy=20.0, sz=20.0):
    x, y, z = sx / 2, sy / 2, sz / 2
    v = np.array([[-x, -y, -z], [x, -y, -z], [x, y, -z], [-x, y, -z],
                  [-x, -y, z], [x, -y, z], [x, y, z], [-x, y, z]], float)
    f = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)]
    return v, f


def tetra(s=10.0):
    v = np.array([[0, 0, 0], [s, 0, 0], [0, s, 0], [0, 0, s]], float)
    f = [(0, 2, 1), (0, 1, 3), (0, 3, 2), (1, 2, 3)]
    return v, f


def torus(R=30.0, r=10.0, nu=64, nv=32):
    u = np.linspace(0, 2 * np.pi, nu, endpoint=False)
    w = np.linspace(0, 2 * np.pi, nv, endpoint=False)
    uu, ww = np.meshgrid(u, w, indexing="ij")
    v = np.stack([(R + r * np.cos(ww)) * np.cos(uu), (R + r * np.cos(ww)) * np.sin(uu), r * np.sin(ww)], -1).reshape(-1, 3)
    return v, grid_quads(nu, nv)


def torus_knot(p=3, q=7, nu=1000, nv=50, R=40.0, r=12.0, tube=4.0):
    """Closed tube along a (p,q) torus knot: 2*nu*nv triangles."""
    t = np.linspace(0, 2 * np.pi, nu, endpoint=False)

    def curve(tt):
        rr = R + r * np.cos(q * tt)
        return np.stack([rr * np.cos(p * tt), rr * np.sin(p * tt), r * np.sin(q * tt)], -1)

    c = curve(t)
    tangent = curve(t + 1e-4) - curve(t - 1e-4)
    tangent /= np.linalg.norm(tangent, axis=1, keepdims=True)
    n = np.cross(tangent, np.array([0.0, 0.0, 1.0]))
    n /= np.linalg.norm(n, axis=1, keepdims=True)
    b = np.cross(tangent, n)
    a = np.linspace(0, 2 * np.pi, nv, endpoint=False)
    v = (c[:, None, :] + tube * (np.cos(a)[None, :, None] * n[:, None, :] + np.sin(a)[None, :, None] * b[:, None, :]))
    # The (n, b) frame runs the tube's circle clockwise as seen along the tangent, so grid_quads' order would
    # wind the faces inwards. Reverse each quad (keeping its diagonal) so the normals point out of the tube.
    return v.reshape(-1, 3), grid_quads(nu, nv)[:, [0, 3, 2, 1]]


def uv_sphere(radius=25.0, nu=64, nv=32):
    """Latitude/longitude sphere with single pole vertices: 2*nu*(nv-1) triangles."""
    verts = [[0, 0, radius]]
    for i in range(1, nv):
        th = math.pi * i / nv
        for j in range(nu):
            ph = 2 * math.pi * j / nu
            verts.append([radius * math.sin(th) * math.cos(ph), radius * math.sin(th) * math.sin(ph), radius * math.cos(th)])
    verts.append([0, 0, -radius])
    bottom = len(verts) - 1
    f = []
    ring = lambda i, j: 1 + (i - 1) * nu + (j % nu)
    for j in range(nu):
        f.append((0, ring(1, j), ring(1, j + 1)))
    for i in range(1, nv - 1):
        for j in range(nu):
            f.append((ring(i, j), ring(i + 1, j), ring(i + 1, j + 1), ring(i, j + 1)))
    for j in range(nu):
        f.append((bottom, ring(nv - 1, j + 1), ring(nv - 1, j)))
    return np.array(verts, float), f


def hex_prism(radius=15.0, height=10.0):
    a = [2 * math.pi * k / 6 for k in range(6)]
    v = [[radius * math.cos(t), radius * math.sin(t), 0] for t in a] + [[radius * math.cos(t), radius * math.sin(t), height] for t in a]
    f = [tuple(range(5, -1, -1)), tuple(range(6, 12))] + [(k, (k + 1) % 6, 6 + (k + 1) % 6, 6 + k) for k in range(6)]
    return np.array(v, float), f


def grid_quads(nu, nv):
    i = np.arange(nu)[:, None]
    j = np.arange(nv)[None, :]
    a = i * nv + j
    b = ((i + 1) % nu) * nv + j
    c = ((i + 1) % nu) * nv + (j + 1) % nv
    d = i * nv + (j + 1) % nv
    return np.stack([a, b, c, d], -1).reshape(-1, 4)


def triangulate(faces):
    """Fan triangulation, matching how every parser in the app splits polygons."""
    if isinstance(faces, np.ndarray) and faces.ndim == 2 and faces.shape[1] == 4:
        return np.concatenate([faces[:, [0, 1, 2]], faces[:, [0, 2, 3]]])
    tris = []
    for f in faces:
        for k in range(1, len(f) - 1):
            tris.append((f[0], f[k], f[k + 1]))
    return np.array(tris, dtype=np.int64)


def triangles_of(v, faces):
    return v[triangulate(faces)]  # (T,3,3)


def apply_affine(points, m4):
    """m4: 4x4 matrix acting on column vectors."""
    p = np.concatenate([points.reshape(-1, 3), np.ones((points.reshape(-1, 3).shape[0], 1))], 1)
    return (p @ m4.T)[:, :3].reshape(points.shape)


def translate(x, y, z):
    m = np.eye(4)
    m[:3, 3] = [x, y, z]
    return m


def rot_z(deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    m = np.eye(4)
    m[:2, :2] = [[c, -s], [s, c]]
    return m


def scale(sx, sy=None, sz=None):
    return np.diag([sx, sy if sy is not None else sx, sz if sz is not None else sx, 1.0])


# --------------------------------------------------------------------------------------------------
# Bookkeeping
# --------------------------------------------------------------------------------------------------

def record(path, fmt, tier, tests, tris=None, expect="ok"):
    rel = path.relative_to(OUT).as_posix()
    entry = {"file": rel, "format": fmt, "tier": tier, "expect": expect, "tests": tests,
             "size_bytes": path.stat().st_size}
    if tris is not None:
        t32 = tris.astype(np.float32).reshape(-1, 3)
        entry["triangles"] = int(tris.shape[0])
        entry["bbox"] = [float(x) for x in np.concatenate([t32.min(0), t32.max(0)])]
        t = tris.reshape(-1, 3, 3).astype(np.float64)
        entry["surface_area"] = float(0.5 * np.linalg.norm(np.cross(t[:, 1] - t[:, 0], t[:, 2] - t[:, 0]), axis=1).sum())
    if fmt == "GLB" and path.suffix == ".glb":
        hashes = meshopt_reference_hashes(path)
        if hashes:
            entry["meshopt_reference_sha256"] = hashes
    ENTRIES.append(entry)
    print(f"  {rel:55s} {entry.get('triangles', '-'):>10} tris  {entry['size_bytes'] / 1e6:8.2f} MB  [{tier}]")


def meshopt_reference_hashes(path):
    """SHA-256 of every meshopt view decoded by meshoptimizer's reference decoder (needs MESHOPTIMIZER_DIR + node)."""
    import os
    import subprocess
    if not os.environ.get("MESHOPTIMIZER_DIR") or b"meshopt_compression" not in path.read_bytes()[:65536]:
        return None
    script = Path(__file__).resolve().parent / "meshopt_reference_hashes.mjs"
    r = subprocess.run(["node", str(script), str(path)], capture_output=True, text=True, env=os.environ)
    if r.returncode != 0:
        raise RuntimeError(f"reference decode failed for {path}:\n{r.stderr}")
    return json.loads(r.stdout)


def out(name):
    p = OUT / name
    p.parent.mkdir(parents=True, exist_ok=True)
    return p


# --------------------------------------------------------------------------------------------------
# Writers
# --------------------------------------------------------------------------------------------------

def write_stl_ascii(path, tris, name="model"):
    with open(path, "w", newline="\n") as fh:
        fh.write(f"solid {name}\n")
        for t in tris:
            n = np.cross(t[1] - t[0], t[2] - t[0])
            n = n / (np.linalg.norm(n) or 1)
            fh.write(f"  facet normal {n[0]:e} {n[1]:e} {n[2]:e}\n    outer loop\n")
            for p in t:
                fh.write(f"      vertex {p[0]:.6f} {p[1]:.6f} {p[2]:.6f}\n")
            fh.write("    endloop\n  endfacet\n")
        fh.write(f"endsolid {name}\n")


def write_stl_binary(path, tris, header=b"Strata:3D test model"):
    t = tris.astype(np.float32)
    n = np.cross(t[:, 1] - t[:, 0], t[:, 2] - t[:, 0])
    n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)
    rec = np.zeros(len(t), dtype=np.dtype([("n", "<f4", 3), ("v", "<f4", (3, 3)), ("attr", "<u2")]))
    rec["n"], rec["v"] = n, t
    with open(path, "wb") as fh:
        fh.write(header.ljust(80, b" ")[:80])
        fh.write(struct.pack("<I", len(t)))
        fh.write(rec.tobytes())


def write_obj(path, text):
    path.write_text(text, newline="\n")


def obj_from(v, faces, header="", fmt="v"):
    lines = [header] if header else []
    lines += [f"v {p[0]:.6f} {p[1]:.6f} {p[2]:.6f}" for p in v]
    if fmt == "v//vn":
        lines += [f"vn {p[0]:.5f} {p[1]:.5f} {p[2]:.5f}" for p in v / np.maximum(np.linalg.norm(v, axis=1, keepdims=True), 1e-9)]
    for f in faces:
        if fmt == "v//vn":
            lines.append("f " + " ".join(f"{i + 1}//{i + 1}" for i in f))
        else:
            lines.append("f " + " ".join(str(i + 1) for i in f))
    return "\n".join(lines) + "\n"


# --------------------------------------------------------------------------------------------------
# Suite
# --------------------------------------------------------------------------------------------------

def gen_stl():
    v, f = tetra()
    t = triangles_of(v, f)
    p = out("stl/01_tetrahedron_ascii.stl"); write_stl_ascii(p, t, "tetra")
    record(p, "STL", "core", "Smallest valid model: ASCII STL, 4 facets.", t)

    v, f = torus(nu=128, nv=64)
    t = triangles_of(v, f)
    p = out("stl/02_torus_16k_binary.stl"); write_stl_binary(p, t)
    record(p, "STL", "core", "Binary STL, 16k triangles, smooth curved surface.", t)

    v, f = box(40, 25, 10)
    t = triangles_of(v, f)
    p = out("stl/03_box_binary_header_says_solid.stl")
    write_stl_binary(p, t, header=b"solid exported-by-cad  (binary file whose header starts with 'solid')")
    record(p, "STL", "real-world", "Binary STL whose 80-byte header begins with 'solid' (a classic CAD-export trap that fools ASCII detection).", t)

    v, f = torus_knot(nu=10000, nv=50)
    t = triangles_of(v, f)
    p = out("stl/04_torus_knot_1M_binary.stl"); write_stl_binary(p, t)
    record(p, "STL", "core", "Stress test: binary STL with 1,000,000 triangles (~50 MB).", t)

    # Deliberately inside-out: a closed torus with every triangle reversed (exports with flipped normals happen).
    v, f = torus()
    t = triangles_of(v, f)[:, ::-1, :]
    p = out("stl/35_inside_out_torus_binary.stl"); write_stl_binary(p, t)
    record(p, "STL", "real-world", "Closed torus with every triangle wound inwards (inside-out); the mesh check must report it.", t)


def gen_obj():
    v, f = box(20, 20, 20)
    t = triangles_of(v, f)
    text = """# Cube exported with UVs, normals, groups and materials
mtllib cube.mtl
o Cube
g body
s off
""" + "\n".join(f"v {p[0]:.4f} {p[1]:.4f} {p[2]:.4f}" for p in v) + """
vt 0 0
vt 1 0
vt 1 1
vt 0 1
vn 0 0 -1
vn 0 0 1
vn 0 -1 0
vn 1 0 0
vn 0 1 0
vn -1 0 0
usemtl Grey
""" + "\n".join(
        f"f {f_[0] + 1}/1/{k + 1} {f_[1] + 1}/2/{k + 1} {f_[2] + 1}/3/{k + 1} \\\n  {f_[3] + 1}/4/{k + 1}" for k, f_ in enumerate(f)) + "\n"
    p = out("obj/05_cube_quads_uv_normals.obj"); write_obj(p, text)
    record(p, "OBJ", "core", "Quads with v/vt/vn indices, o/g/s/usemtl/mtllib lines (missing .mtl), and a backslash line continuation.", t)

    v, f = hex_prism()
    t = triangles_of(v, f)
    n = len(v)
    lines = ["# Hexagonal prism: 6-gon caps and quad sides written with negative (relative) indices"]
    lines += [f"v {p[0]:.6f} {p[1]:.6f} {p[2]:.6f}" for p in v]
    lines += ["f " + " ".join(str(i - n) for i in face) for face in f]
    p = out("obj/06_hex_prism_ngons_negative_indices.obj"); write_obj(p, "\n".join(lines) + "\n")
    record(p, "OBJ", "core", "Convex 6-gon faces (fan triangulated) and negative relative indices.", t)

    v, f = uv_sphere(nu=400, nv=250)
    t = triangles_of(v, f)
    p = out("obj/07_sphere_199k_v_vn.obj"); write_obj(p, obj_from(v, f, "# UV sphere, v//vn faces", fmt="v//vn"))
    record(p, "OBJ", "core", "199,200-triangle UV sphere using 'v//vn' face syntax.", t)


def ply_header(fmt, vprops, nverts, fprops, nfaces, extra=""):
    h = ["ply", f"format {fmt} 1.0", "comment generated by Strata:3D test suite", "obj_info test asset", f"element vertex {nverts}"]
    h += [f"property {p}" for p in vprops]
    h += [f"element face {nfaces}"] + [f"property {p}" for p in fprops]
    if extra:
        h.append(extra)
    h.append("end_header")
    return ("\n".join(h) + "\n").encode("ascii")


def gen_ply():
    v, f = box(30, 20, 10)
    t = triangles_of(v, f)
    body = [f"{p[0]} {p[1]} {p[2]} 0 0 1 200 40 40" for p in v]
    body += [f"4 {a} {b} {c} {d} 7" for a, b, c, d in f]
    p = out("ply/08_box_ascii_normals_colors.ply")
    p.write_bytes(ply_header("ascii", ["float x", "float y", "float z", "float nx", "float ny", "float nz",
                                       "uchar red", "uchar green", "uchar blue"], len(v),
                             ["list uchar int vertex_indices", "uchar flags"], len(f)) + ("\n".join(body) + "\n").encode())
    record(p, "PLY", "core", "ASCII PLY with normals + colours per vertex, quad faces, and an extra face property after the list.", t)

    v, f = torus(R=35, r=12, nu=320, nv=160)
    t = triangles_of(v, f)
    vrec = np.zeros(len(v), dtype=np.dtype([("x", "<f4"), ("y", "<f4"), ("z", "<f4"), ("nx", "<f4"), ("ny", "<f4"), ("nz", "<f4"),
                                            ("r", "u1"), ("g", "u1"), ("b", "u1"), ("a", "u1")]))
    vrec["x"], vrec["y"], vrec["z"] = v[:, 0], v[:, 1], v[:, 2]
    vrec["r"], vrec["a"] = 180, 255
    frec = np.zeros(len(f), dtype=np.dtype([("n", "u1"), ("i", "<i4", 4)]))
    frec["n"], frec["i"] = 4, f
    p = out("ply/09_torus_102k_binary_le.ply")
    p.write_bytes(ply_header("binary_little_endian", ["float x", "float y", "float z", "float nx", "float ny", "float nz",
                                                     "uchar red", "uchar green", "uchar blue", "uchar alpha"], len(v),
                             ["list uchar int vertex_indices"], len(f)) + vrec.tobytes() + frec.tobytes())
    record(p, "PLY", "core", "Binary little-endian PLY (scanner style): 102,400 triangles, float xyz + normals + RGBA.", t)

    v, f = uv_sphere(radius=12.5, nu=48, nv=24)
    tri = triangulate(f)
    t = v[tri]
    vrec = np.zeros(len(v), dtype=np.dtype([("x", ">f8"), ("y", ">f8"), ("z", ">f8")]))
    vrec["x"], vrec["y"], vrec["z"] = v[:, 0], v[:, 1], v[:, 2]
    frec = np.zeros(len(tri), dtype=np.dtype([("n", "u1"), ("i", ">u4", 3)]))
    frec["n"], frec["i"] = 3, tri
    edges = np.zeros(5, dtype=np.dtype([("a", ">i4"), ("b", ">i4")]))
    p = out("ply/10_sphere_binary_be_double_with_edges.ply")
    p.write_bytes(ply_header("binary_big_endian", ["double x", "double y", "double z"], len(v),
                             ["list uchar uint vertex_index"], len(tri),
                             extra="element edge 5\nproperty int vertex1\nproperty int vertex2") + vrec.tobytes() + frec.tobytes() + edges.tobytes())
    record(p, "PLY", "core", "Binary big-endian PLY: double-precision coordinates, 'vertex_index' list name, and a trailing 'edge' element to skip.", t)

    v, f = box(10, 10, 10)
    tri = triangulate(f)
    t = v[tri]
    vrec = np.zeros(len(v), dtype=np.dtype([("x", "<f4"), ("y", "<f4"), ("z", "<f4"), ("q", "<i2")]))
    vrec["x"], vrec["y"], vrec["z"], vrec["q"] = v[:, 0], v[:, 1], v[:, 2], -3
    frec = np.zeros(len(tri), dtype=np.dtype([("n", "u1"), ("i", "<u2", 3)]))
    frec["n"], frec["i"] = 3, tri
    p = out("ply/11_box_binary_le_ushort_indices.ply")
    p.write_bytes(ply_header("binary_little_endian", ["float x", "float y", "float z", "short quality"], len(v),
                             ["list uchar ushort vertex_indices"], len(tri)) + vrec.tobytes() + frec.tobytes())
    record(p, "PLY", "real-world", "Binary little-endian PLY with 16-bit (ushort) vertex indices, as written by some exporters for small meshes.", t)

    frec = np.zeros(len(tri), dtype=np.dtype([("n", "<u2"), ("i", "<i4", 3)]))
    frec["n"], frec["i"] = 3, tri
    p = out("ply/12_box_binary_le_ushort_list_count.ply")
    p.write_bytes(ply_header("binary_little_endian", ["float x", "float y", "float z", "short quality"], len(v),
                             ["list ushort int vertex_indices"], len(tri)) + vrec.tobytes() + frec.tobytes())
    record(p, "PLY", "real-world", "Binary little-endian PLY whose face list count is a 16-bit ushort.", t)


NS = "http://schemas.microsoft.com/3dmanufacturing/core/2015/02"
NS_PROD = "http://schemas.microsoft.com/3dmanufacturing/production/2015/06"
CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
 <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
 <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
</Types>"""
RELS = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
</Relationships>"""


def mesh_xml(v, tri):
    vs = "".join(f'<vertex x="{p[0]:.6f}" y="{p[1]:.6f}" z="{p[2]:.6f}"/>' for p in v)
    ts = "".join(f'<triangle v1="{a}" v2="{b}" v3="{c}"/>' for a, b, c in tri)
    return f"<mesh><vertices>{vs}</vertices><triangles>{ts}</triangles></mesh>"


def tf3mf(m4):
    """4x4 column-vector matrix -> 3MF 12-value row-vector transform string."""
    r, tr = m4[:3, :3], m4[:3, 3]
    vals = [r[0, 0], r[1, 0], r[2, 0], r[0, 1], r[1, 1], r[2, 1], r[0, 2], r[1, 2], r[2, 2], tr[0], tr[1], tr[2]]
    return " ".join(f"{x:.6f}" for x in vals)


def write_3mf(path, parts):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in parts:
            z.writestr(name, data)


def gen_3mf():
    cv, cf = box(20, 20, 20)
    ctri = triangulate(cf)
    tv, tf = tetra(15)
    ttri = triangulate(tf)
    items = [(1, np.eye(4)), (1, translate(40, 0, 0)), (2, translate(0, 40, 0) @ rot_z(90))]
    t = np.concatenate([apply_affine(cv[ctri], m) if oid == 1 else apply_affine(tv[ttri], m) for oid, m in items])
    model = f"""<?xml version="1.0" encoding="UTF-8"?>
<model unit="millimeter" xml:lang="en-US" xmlns="{NS}">
 <metadata name="Title">Instanced objects</metadata>
 <resources>
  <object id="1" type="model">{mesh_xml(cv, ctri)}</object>
  <object id="2" type="model">{mesh_xml(tv, ttri)}</object>
 </resources>
 <build>{"".join(f'<item objectid="{oid}" transform="{tf3mf(m)}"/>' for oid, m in items)}</build>
</model>"""
    p = out("3mf/13_instanced_objects_transforms.3mf")
    write_3mf(p, [("[Content_Types].xml", CONTENT_TYPES), ("_rels/.rels", RELS), ("3D/3dmodel.model", model)])
    record(p, "3MF", "core", "Two mesh objects, one instanced twice; build-item translation + rotation transforms.", t)

    # Multi-part object built from <components> (how PrusaSlicer, Cura and others save multi-part objects).
    comps = [(1, np.eye(4)), (2, translate(25, 0, 0))]
    item = translate(0, 0, 10)
    t = np.concatenate([apply_affine(apply_affine(cv[ctri] if oid == 1 else tv[ttri], m), item) for oid, m in comps])
    model = f"""<?xml version="1.0" encoding="UTF-8"?>
<model unit="millimeter" xmlns="{NS}">
 <resources>
  <object id="1" type="model">{mesh_xml(cv, ctri)}</object>
  <object id="2" type="model">{mesh_xml(tv, ttri)}</object>
  <object id="3" type="model"><components>{"".join(f'<component objectid="{oid}" transform="{tf3mf(m)}"/>' for oid, m in comps)}</components></object>
 </resources>
 <build><item objectid="3" transform="{tf3mf(item)}"/></build>
</model>"""
    p = out("3mf/14_multipart_components.3mf")
    write_3mf(p, [("[Content_Types].xml", CONTENT_TYPES), ("_rels/.rels", RELS), ("3D/3dmodel.model", model)])
    record(p, "3MF", "real-world", "Multi-part object: a build item that points at an object made of <components> (slicer-style).", t)

    # Production extension: mesh lives in a separate part (Bambu Studio / Orca Slicer project layout).
    kv, kf = torus(R=20, r=6, nu=48, nv=24)
    ktri = triangulate(kf)
    item = translate(100, 100, 6)
    t = apply_affine(kv[ktri], item)
    obj_part = f"""<?xml version="1.0" encoding="UTF-8"?>
<model unit="millimeter" xmlns="{NS}"><resources><object id="1" type="model">{mesh_xml(kv, ktri)}</object></resources><build/></model>"""
    root = f"""<?xml version="1.0" encoding="UTF-8"?>
<model unit="millimeter" xmlns="{NS}" xmlns:p="{NS_PROD}" requiredextensions="p">
 <resources>
  <object id="2" p:UUID="00000002-81cb-4c03-9d28-80fed5dfa1dc" type="model">
   <components><component p:path="/3D/Objects/object_1.model" objectid="1" transform="1 0 0 0 1 0 0 0 1 0 0 0"/></components>
  </object>
 </resources>
 <build><item objectid="2" transform="{tf3mf(item)}" printable="1"/></build>
</model>"""
    rels = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Target="/3D/Objects/object_1.model" Id="rel-1" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
</Relationships>"""
    p = out("3mf/15_production_ext_separate_part.3mf")
    write_3mf(p, [("[Content_Types].xml", CONTENT_TYPES), ("_rels/.rels", RELS), ("3D/Objects/object_1.model", obj_part),
                  ("3D/_rels/3dmodel.model.rels", rels), ("3D/3dmodel.model", root)])
    record(p, "3MF", "real-world", "Production-extension layout (Bambu Studio / Orca Slicer): the mesh lives in 3D/Objects/object_1.model, referenced by p:path.", t)

    v, f = torus_knot(p=2, q=5, nu=3000, nv=50)
    tri = triangulate(f)
    t = v[tri]
    model = f"""<?xml version="1.0" encoding="UTF-8"?>
<model unit="millimeter" xmlns="{NS}"><resources><object id="1" type="model">{mesh_xml(v, tri)}</object></resources><build><item objectid="1"/></build></model>"""
    p = out("3mf/16_torus_knot_300k.3mf")
    write_3mf(p, [("[Content_Types].xml", CONTENT_TYPES), ("_rels/.rels", RELS), ("3D/3dmodel.model", model)])
    record(p, "3MF", "core", "300,000-triangle mesh in a compressed 3MF package.", t)


class GltfBuilder:
    def __init__(self):
        self.bin = bytearray()
        self.views, self.accessors = [], []

    def _align(self, n=4):
        while len(self.bin) % n:
            self.bin.append(0)

    def view(self, data: bytes, stride=None, target=None):
        self._align()
        bv = {"buffer": 0, "byteOffset": len(self.bin), "byteLength": len(data)}
        if stride:
            bv["byteStride"] = stride
        if target:
            bv["target"] = target
        self.bin += data
        self.views.append(bv)
        return len(self.views) - 1

    def accessor(self, view, comp, count, typ, offset=0, minmax=None, normalized=False):
        a = {"bufferView": view, "componentType": comp, "count": count, "type": typ}
        if offset:
            a["byteOffset"] = offset
        if minmax is not None:
            a["min"], a["max"] = [float(x) for x in minmax[0]], [float(x) for x in minmax[1]]
        if normalized:
            a["normalized"] = True
        self.accessors.append(a)
        return len(self.accessors) - 1

    def positions(self, v):
        v32 = v.astype("<f4")
        return self.accessor(self.view(v32.tobytes(), target=34962), 5126, len(v), "VEC3", minmax=(v32.min(0), v32.max(0)))

    def indices(self, idx, dtype):
        comp = {"u1": 5121, "<u2": 5123, "<u4": 5125}[dtype]
        return self.accessor(self.view(np.asarray(idx, dtype).tobytes(), target=34963), comp, len(np.ravel(idx)), "SCALAR")

    def doc(self, meshes, nodes, scenes, scene=0, extra=None):
        d = {"asset": {"version": "2.0", "generator": "Strata:3D test suite"}, "scene": scene, "scenes": scenes,
             "nodes": nodes, "meshes": meshes, "accessors": self.accessors, "bufferViews": self.views,
             "buffers": [{"byteLength": len(self.bin)}]}
        if extra:
            d.update(extra)
        return d

    def write_glb(self, path, d):
        self._align()
        js = json.dumps(d, separators=(",", ":")).encode()
        js += b" " * ((4 - len(js) % 4) % 4)
        total = 12 + 8 + len(js) + 8 + len(self.bin)
        with open(path, "wb") as fh:
            fh.write(struct.pack("<III", 0x46546C67, 2, total))
            fh.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
            fh.write(struct.pack("<II", len(self.bin), 0x004E4942) + bytes(self.bin))

    def write_gltf(self, path, d):
        d["buffers"][0]["uri"] = "data:application/octet-stream;base64," + base64.b64encode(bytes(self.bin)).decode()
        path.write_text(json.dumps(d, indent=1), newline="\n")


def quat_z(deg):
    h = math.radians(deg) / 2
    return [0.0, 0.0, math.sin(h), math.cos(h)]


def trs(t=(0, 0, 0), q=(0, 0, 0, 1), s=(1, 1, 1)):
    x, y, z, w = q
    r = np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                  [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                  [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
    m = np.eye(4)
    m[:3, :3] = r @ np.diag(s)
    m[:3, 3] = t
    return m


def gen_gltf():
    # 17: embedded .gltf, uint16 indices.
    v, f = box(10, 20, 30)
    tri = triangulate(f)
    g = GltfBuilder()
    pos = g.positions(v)
    idx = g.indices(tri, "<u2")
    d = g.doc([{"primitives": [{"attributes": {"POSITION": pos}, "indices": idx}]}], [{"mesh": 0}], [{"nodes": [0]}])
    p = out("gltf/17_box_embedded_base64.gltf"); g.write_gltf(p, d)
    record(p, "glTF", "core", "Text .gltf with its buffer embedded as a base64 data: URI, uint16 indices.", v[tri])

    # 18: scene graph + interleaved attributes + several index types + default-scene selection.
    g = GltfBuilder()
    bv, bf = box(4, 4, 4)
    btri = triangulate(bf)
    normals = bv / np.linalg.norm(bv, axis=1, keepdims=True)
    inter = np.concatenate([bv, normals], 1).astype("<f4")  # POSITION + NORMAL interleaved, stride 24
    iv = g.view(inter.tobytes(), stride=24, target=34962)
    b32 = bv.astype("<f4")
    bpos = g.accessor(iv, 5126, len(bv), "VEC3", offset=0, minmax=(b32.min(0), b32.max(0)))
    bnrm = g.accessor(iv, 5126, len(bv), "VEC3", offset=12)
    bidx = g.indices(btri, "u1")
    tv, tf = tetra(3)
    ttri = triangulate(tf)
    tpos = g.positions(tv)
    tidx = g.indices(ttri, "<u4")
    meshes = [{"primitives": [{"attributes": {"POSITION": bpos, "NORMAL": bnrm}, "indices": bidx},
                              {"attributes": {"POSITION": tpos}, "indices": tidx}]},
              {"primitives": [{"attributes": {"POSITION": tpos}, "indices": tidx}]}]
    n0 = trs(t=(10, 0, 0), s=(2, 2, 2))
    n1 = trs(q=quat_z(90))
    n2 = translate(0, 5, 0)
    n3 = trs(t=(0, 0, 3))
    nodes = [{"name": "root", "children": [1, 2], "translation": [10, 0, 0], "scale": [2, 2, 2]},
             {"name": "rotated", "mesh": 0, "rotation": quat_z(90)},
             {"name": "matrix", "matrix": [float(x) for x in n2.T.ravel()], "children": [3]},
             {"name": "reused", "mesh": 0, "translation": [0, 0, 3]},
             {"name": "other-scene-only", "mesh": 1, "translation": [500, 500, 500]}]
    m0 = np.concatenate([bv[btri], tv[ttri]])
    t = np.concatenate([apply_affine(m0, n0 @ n1), apply_affine(m0, n0 @ n2 @ n3)])
    d = g.doc(meshes, nodes, [{"nodes": [4]}, {"nodes": [0]}], scene=1)
    p = out("gltf/18_scene_graph_interleaved.glb"); g.write_glb(p, d)
    record(p, "GLB", "core", "Node hierarchy (TRS + matrix), one mesh reused by two nodes, interleaved POSITION/NORMAL (byteStride 24), uint8 + uint32 indices, 'scene': 1 chosen over scene 0.", t)

    # 19: strip, fan, non-indexed triangles, and a LINES primitive that must be skipped.
    g = GltfBuilder()
    strip = np.array([[0, 0, 0], [0, 10, 0], [10, 0, 0], [10, 10, 0], [20, 0, 0], [20, 10, 0]], float)
    fan = np.array([[0, 0, 5]] + [[8 * math.cos(a), 8 * math.sin(a), 5] for a in np.linspace(0, math.pi, 5)], float) + [30, 0, 0]
    soup = np.array([[0, 20, 0], [5, 30, 0], [10, 20, 0]], float)
    line = np.array([[-50, -50, -50], [50, 50, 50]], float)
    sp, fp, tp, lp = g.positions(strip), g.positions(fan), g.positions(soup), g.positions(line)
    meshes = [{"primitives": [{"attributes": {"POSITION": sp}, "mode": 5}, {"attributes": {"POSITION": fp}, "mode": 6},
                              {"attributes": {"POSITION": tp}}, {"attributes": {"POSITION": lp}, "mode": 1}]}]
    st = [strip[[i, i + 1, i + 2]] if i % 2 == 0 else strip[[i, i + 2, i + 1]] for i in range(4)]
    ft = [fan[[0, i, i + 1]] for i in range(1, 5)]
    t = np.array(st + ft + [soup])
    d = g.doc(meshes, [{"mesh": 0}], [{"nodes": [0]}])
    p = out("gltf/19_strip_fan_soup_lines.glb"); g.write_glb(p, d)
    record(p, "GLB", "core", "TRIANGLE_STRIP, TRIANGLE_FAN and non-indexed TRIANGLES primitives; a LINES primitive is ignored (its far-away points must not affect the bounds).", t)

    # 20: large GLB.
    g = GltfBuilder()
    v, f = torus_knot(p=3, q=4, nu=5000, nv=50)
    tri = triangulate(f)
    pos, idx = g.positions(v), g.indices(tri, "<u4")
    d = g.doc([{"primitives": [{"attributes": {"POSITION": pos}, "indices": idx}]}], [{"mesh": 0}], [{"nodes": [0]}])
    p = out("gltf/20_torus_knot_500k.glb"); g.write_glb(p, d)
    record(p, "GLB", "core", "500,000-triangle GLB with uint32 indices.", v[tri])

    # 21: KHR_mesh_quantization (int16 normalized positions) - common in web-optimised assets.
    g = GltfBuilder()
    v, f = box(2, 2, 2)
    tri = triangulate(f)
    q = np.round(v / 1.0 * 32767).astype("<i2")
    qv = g.view(np.concatenate([q, np.zeros((len(q), 1), "<i2")], 1).tobytes(), stride=8, target=34962)
    pos = g.accessor(qv, 5122, len(v), "VEC3", minmax=(q.min(0), q.max(0)), normalized=True)
    idx = g.indices(tri, "<u2")
    d = g.doc([{"primitives": [{"attributes": {"POSITION": pos}, "indices": idx}]}], [{"mesh": 0, "scale": [10, 10, 10]}],
              [{"nodes": [0]}], extra={"extensionsUsed": ["KHR_mesh_quantization"], "extensionsRequired": ["KHR_mesh_quantization"]})
    p = out("gltf/21_khr_mesh_quantization.glb"); g.write_glb(p, d)
    record(p, "GLB", "core", "KHR_mesh_quantization: normalized 16-bit positions (padded to an 8-byte stride) scaled up by the node.", (np.round(v * 32767) / 32767 * 10)[tri])

    # 22: external .bin buffer (documented limitation).
    g = GltfBuilder()
    v, f = box()
    tri = triangulate(f)
    pos, idx = g.positions(v), g.indices(tri, "<u2")
    d = g.doc([{"primitives": [{"attributes": {"POSITION": pos}, "indices": idx}]}], [{"mesh": 0}], [{"nodes": [0]}])
    d["buffers"][0]["uri"] = "22_external_buffer.bin"
    p = out("gltf/22_external_buffer.gltf")
    p.write_text(json.dumps(d, indent=1), newline="\n")
    out("gltf/22_external_buffer.bin").write_bytes(bytes(g.bin))
    record(p, "glTF", "core", "glTF whose buffer is a separate .bin file next to it (open both files together, or a .zip).", v[tri])

    # 26: sparse accessor over an accessor with no bufferView (base data is all zeros).
    g = GltfBuilder()
    tri_pts = np.array([[0, 0, 0], [12, 0, 0], [0, 7, 3], [0, 0, 0]], float)  # vertex 3 stays at the zero base value
    sparse_idx = g.view(np.array([1, 2], "<u2").tobytes())
    sparse_val = g.view(tri_pts[[1, 2]].astype("<f4").tobytes())
    pos = len(g.accessors)
    g.accessors.append({"componentType": 5126, "count": 4, "type": "VEC3", "min": [0, 0, 0], "max": [12, 7, 3],
                        "sparse": {"count": 2, "indices": {"bufferView": sparse_idx, "componentType": 5123},
                                   "values": {"bufferView": sparse_val}}})
    idx = g.indices(np.array([0, 1, 2, 3, 1, 2]), "u1")
    d = g.doc([{"primitives": [{"attributes": {"POSITION": pos}, "indices": idx}]}], [{"mesh": 0}], [{"nodes": [0]}])
    p = out("gltf/26_sparse_accessor_no_bufferview.glb"); g.write_glb(p, d)
    record(p, "GLB", "core", "POSITION accessor with no bufferView (zeros) patched by a sparse accessor.", tri_pts[[0, 1, 2, 3, 1, 2]].reshape(2, 3, 3))

    # 31: external .bin that was not provided.
    g = GltfBuilder()
    v, f = box()
    tri = triangulate(f)
    pos, idx = g.positions(v), g.indices(tri, "<u2")
    d = g.doc([{"primitives": [{"attributes": {"POSITION": pos}, "indices": idx}]}], [{"mesh": 0}], [{"nodes": [0]}])
    d["buffers"][0]["uri"] = "missing%20buffer.bin"
    p = out("errors/31_external_bin_missing.gltf")
    p.write_text(json.dumps(d, indent=1), newline="\n")
    record(p, "glTF", "limitation", "glTF whose .bin was not provided. Must explain how to open it (select both files, or a .zip).", expect="error")


def gltf_transform_command():
    """Path to the glTF-Transform CLI (https://gltf-transform.dev), from GLTF_TRANSFORM or PATH."""
    import os
    import shutil
    return os.environ.get("GLTF_TRANSFORM") or shutil.which("gltf-transform")


def run_gltf_transform(cmd, *args):
    import subprocess
    r = subprocess.run(f'"{cmd}" ' + " ".join(f'"{a}"' for a in args), shell=True, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"gltf-transform {args[0]} failed:\n{r.stdout}\n{r.stderr}")


def gen_tool_made():
    """Files produced by glTF-Transform, the same tool many real pipelines use to compress glTF assets."""
    cmd = gltf_transform_command()
    if not cmd:
        print("  (skipping Draco/quantize/meshopt files: set GLTF_TRANSFORM to the gltf-transform CLI)")
        return
    work = OUT / "_work"
    work.mkdir(exist_ok=True)

    v, f = torus_knot(p=2, q=3, nu=2000, nv=50)
    tri = triangulate(f)
    base = work / "base.glb"
    g = GltfBuilder()
    pos, idx = g.positions(v), g.indices(tri, "<u4")
    g.write_glb(base, g.doc([{"primitives": [{"attributes": {"POSITION": pos}, "indices": idx}]}], [{"mesh": 0}], [{"nodes": [0]}]))
    expected = v[tri]

    p = out("gltf/25_draco_torus_knot_200k.glb")
    run_gltf_transform(cmd, "draco", base, p)
    record(p, "GLB", "core", "Draco-compressed (KHR_draco_mesh_compression) 200,000-triangle mesh, made with glTF-Transform.", expected)

    p = out("gltf/27_quantized_by_tool.glb")
    run_gltf_transform(cmd, "quantize", base, p)
    record(p, "GLB", "core", "KHR_mesh_quantization as written by glTF-Transform's quantize command.", expected)

    p = out("gltf/28_draco_external_bin.gltf")
    run_gltf_transform(cmd, "draco", base, p)
    record(p, "glTF", "core", "Draco-compressed .gltf with its data in a separate .bin file (open both together, or a .zip).", expected)

    p = out("gltf/30_meshopt.glb")
    run_gltf_transform(cmd, "meshopt", base, p)
    record(p, "GLB", "core", "EXT_meshopt_compression as written by glTF-Transform (level high): quantized positions + triangle index codec.", expected)

    p = out("gltf/34_meshopt_medium.glb")
    run_gltf_transform(cmd, "meshopt", base, p, "--level", "medium")
    record(p, "GLB", "core", "EXT_meshopt_compression as written by glTF-Transform at level medium.", expected)

    for leftover in work.iterdir():
        leftover.unlink()
    work.rmdir()


def meshopt_encode(raw: bytes, mode, count, stride, version=0, filt="NONE", bits=16):
    """Encodes one buffer view with meshoptimizer's own encoder (tools/meshopt_encode.mjs)."""
    import os
    import subprocess
    import tempfile
    script = Path(__file__).resolve().parent / "meshopt_encode.mjs"
    with tempfile.TemporaryDirectory() as tmp:
        src, dst = Path(tmp) / "in.bin", Path(tmp) / "out.bin"
        src.write_bytes(raw)
        r = subprocess.run(["node", str(script), str(src), str(dst), mode, str(count), str(stride), str(version), filt, str(bits)],
                           capture_output=True, text=True, env=os.environ)
        if r.returncode != 0:
            raise RuntimeError(f"meshopt encode failed:\n{r.stdout}\n{r.stderr}")
        return dst.read_bytes()


def write_meshopt_glb(path, ext, views, accessors, primitive):
    """views: list of (encoded, mode, count, stride, filter, target, vertex_stride) -> GLB with a fallback buffer."""
    binary = bytearray()
    buffer_views = []
    fallback_len = 0
    for encoded, mode, count, stride, filt, target, vstride in views:
        while len(binary) % 4:
            binary.append(0)
        meta = {"buffer": 0, "byteOffset": len(binary), "byteLength": len(encoded), "byteStride": stride, "count": count, "mode": mode}
        if filt != "NONE":
            meta["filter"] = filt
        bv = {"buffer": 1, "byteOffset": fallback_len, "byteLength": count * stride, "target": target, "extensions": {ext: meta}}
        if vstride:
            bv["byteStride"] = vstride
        buffer_views.append(bv)
        binary += encoded
        fallback_len += count * stride
    while len(binary) % 4:
        binary.append(0)
    d = {"asset": {"version": "2.0", "generator": "Strata:3D test suite"}, "extensionsUsed": [ext], "extensionsRequired": [ext],
         "scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}], "meshes": [{"primitives": [primitive]}],
         "accessors": accessors, "bufferViews": buffer_views,
         "buffers": [{"byteLength": len(binary)}, {"byteLength": fallback_len, "extensions": {ext: {"fallback": True}}}]}
    js = json.dumps(d, separators=(",", ":")).encode()
    js += b" " * ((4 - len(js) % 4) % 4)
    with open(path, "wb") as fh:
        fh.write(struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(js) + 8 + len(binary)))
        fh.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
        fh.write(struct.pack("<II", len(binary), 0x004E4942) + bytes(binary))


def gen_meshopt_cases():
    """Meshopt paths glTF-Transform does not produce: EXPONENTIAL filter, vertex codec v1, index sequences."""
    import os
    if not os.environ.get("MESHOPTIMIZER_DIR"):
        print("  (skipping hand-built meshopt files: set MESHOPTIMIZER_DIR to the meshoptimizer npm package)")
        return

    # 32: float positions + EXPONENTIAL filter, vertex codec v1, KHR_meshopt_compression, uint32 triangle codec.
    v, f = torus_knot(p=3, q=5, nu=500, nv=40)
    tri = triangulate(f).astype("<u4")
    v32 = v.astype("<f4")
    views = [
        (meshopt_encode(v32.tobytes(), "ATTRIBUTES", len(v), 12, version=1, filt="EXPONENTIAL", bits=16),
         "ATTRIBUTES", len(v), 12, "EXPONENTIAL", 34962, 12),
        (meshopt_encode(tri.tobytes(), "TRIANGLES", tri.size, 4), "TRIANGLES", tri.size, 4, "NONE", 34963, None),
    ]
    accessors = [{"bufferView": 0, "componentType": 5126, "count": len(v), "type": "VEC3",
                  "min": [float(x) for x in v32.min(0)], "max": [float(x) for x in v32.max(0)]},
                 {"bufferView": 1, "componentType": 5125, "count": int(tri.size), "type": "SCALAR"}]
    p = out("gltf/32_meshopt_v1_exponential_khr.glb")
    write_meshopt_glb(p, "KHR_meshopt_compression", views, accessors, {"attributes": {"POSITION": 0}, "indices": 1})
    record(p, "GLB", "core", "KHR_meshopt_compression: float positions with the EXPONENTIAL filter, vertex codec v1, 32-bit triangle codec.", v[tri])

    # 33: indexed TRIANGLE_STRIP, so the indices use the INDICES (sequence) codec; vertex codec v0, uint16.
    n = 101
    strip_v = np.array([[x * 2.0, (k % 2) * 6.0, math.sin(x / 8.0) * 3.0] for x in range(n) for k in range(2)])
    order = np.arange(len(strip_v), dtype="<u2")
    sv32 = strip_v.astype("<f4")
    views = [
        (meshopt_encode(sv32.tobytes(), "ATTRIBUTES", len(sv32), 12), "ATTRIBUTES", len(sv32), 12, "NONE", 34962, 12),
        (meshopt_encode(np.pad(order, (0, order.size % 2)).tobytes(), "INDICES", order.size + order.size % 2, 2),
         "INDICES", order.size + order.size % 2, 2, "NONE", 34963, None),
    ]
    accessors = [{"bufferView": 0, "componentType": 5126, "count": len(sv32), "type": "VEC3",
                  "min": [float(x) for x in sv32.min(0)], "max": [float(x) for x in sv32.max(0)]},
                 {"bufferView": 1, "componentType": 5123, "count": int(order.size), "type": "SCALAR"}]
    p = out("gltf/33_meshopt_strip_index_sequence.glb")
    write_meshopt_glb(p, "EXT_meshopt_compression", views, accessors, {"attributes": {"POSITION": 0}, "indices": 1, "mode": 5})
    st = [strip_v[[i, i + 1, i + 2]] if i % 2 == 0 else strip_v[[i, i + 2, i + 1]] for i in range(len(order) - 2)]
    record(p, "GLB", "core", "EXT_meshopt_compression on an indexed TRIANGLE_STRIP: the indices use the INDICES sequence codec (uint16).", np.array(st))


def gen_zip():
    """A .zip package: model in a subfolder with its .bin and an (ignored) texture, plus macOS junk entries."""
    v, f = torus(R=25, r=8, nu=96, nv=48)
    tri = triangulate(f)
    g = GltfBuilder()
    pos, idx = g.positions(v), g.indices(tri, "<u2")
    d = g.doc([{"primitives": [{"attributes": {"POSITION": pos}, "indices": idx}]}], [{"mesh": 0}], [{"nodes": [0]}],
              extra={"images": [{"uri": "textures/base%20color.png"}]})
    d["buffers"][0]["uri"] = "data/scene%20data.bin"
    p = out("zip/29_gltf_package.zip")
    with zipfile.ZipFile(p, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("readme.txt", "Downloaded asset\n")
        z.writestr("model/scene.gltf", json.dumps(d, indent=1))
        z.writestr("model/data/scene data.bin", bytes(g.bin))
        z.writestr("model/textures/base color.png", b"\x89PNG\r\n\x1a\n")
        z.writestr("__MACOSX/model/._scene.gltf", b"junk")
    record(p, "ZIP", "core", "A .zip with the glTF in a subfolder, its buffer in data/ (URI with %20), a texture, and macOS junk entries.", v[tri])


def gen_errors():
    v, f = torus(nu=32, nv=16)
    g = GltfBuilder()
    tri = triangulate(f)
    pos, idx = g.positions(v), g.indices(tri, "<u2")
    d = g.doc([{"primitives": [{"attributes": {"POSITION": pos}, "indices": idx}]}], [{"mesh": 0}], [{"nodes": [0]}])
    tmp = out("errors/23_truncated.glb")
    g.write_glb(tmp, d)
    data = tmp.read_bytes()
    tmp.write_bytes(data[: len(data) // 2])
    record(tmp, "GLB", "limitation", "GLB cut off halfway (damaged download). Must show a clear error, not crash.", expect="error")

    p = out("errors/24_not_a_model.stl")
    p.write_text("This is a plain text note that someone renamed to .stl\n", newline="\n")
    record(p, "STL", "limitation", "A text file renamed to .stl. Must show a clear error, not crash.", expect="error")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    print(f"Writing test models to {OUT}")
    gen_stl()
    gen_obj()
    gen_ply()
    gen_3mf()
    gen_gltf()
    gen_tool_made()
    gen_meshopt_cases()
    gen_zip()
    gen_errors()
    (OUT / "expected.json").write_text(json.dumps({"generator": "tools/generate_test_models.py", "models": ENTRIES}, indent=1), newline="\n")
    print(f"{len(ENTRIES)} models, expected.json written")


if __name__ == "__main__":
    main()
