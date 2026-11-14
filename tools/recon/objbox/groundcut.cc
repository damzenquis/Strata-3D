/*
 * groundcut: removes the supporting surface (the desk, the floor) from a mesh of a scene aligned by objbox.
 *
 * In an aligned scene the surface is the plane z = 0 and +z is up. Dense reconstruction rebuilds that surface as a
 * thin, slightly noisy layer just above the plane (measured on the A17: 0.3-1.5 % of the camera distance). Raising
 * the box bottom would also shave that much off every object's base, so instead this removes only faces that are
 * both low (z below --height) and facing up (normal within --angle degrees of +z): the visible desk. The sides of
 * the object's base face sideways and are kept. Run meshclean afterwards to drop the scraps left behind.
 *
 * Usage: groundcut [--height=H] [--angle=30] IN.ply OUT.ply
 *   H is in scene units; run_fast.sh passes 2 % of the median camera distance (objbox_report.txt).
 */
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#include "mve/mesh.h"
#include "mve/mesh_io_ply.h"

int main(int argc, char** argv) {
    double height = -1.0, angleDeg = 30.0;
    std::string in, out;
    for (int i = 1; i < argc; ++i) {
        if (std::strncmp(argv[i], "--height=", 9) == 0) height = std::atof(argv[i] + 9);
        else if (std::strncmp(argv[i], "--angle=", 8) == 0) angleDeg = std::atof(argv[i] + 8);
        else if (in.empty()) in = argv[i];
        else out = argv[i];
    }
    if (in.empty() || out.empty() || !(height > 0) || !(angleDeg > 0 && angleDeg < 90)) {
        std::fprintf(stderr, "usage: groundcut --height=H [--angle=30] IN.ply OUT.ply\n");
        return 1;
    }
    mve::TriangleMesh::Ptr mesh;
    try { mesh = mve::geom::load_ply_mesh(in); } catch (std::exception const& e) {
        std::fprintf(stderr, "groundcut: cannot read %s: %s\n", in.c_str(), e.what()); return 1;
    }
    mve::TriangleMesh::VertexList const& v = mesh->get_vertices();
    mve::TriangleMesh::FaceList& f = mesh->get_faces();
    const double minUp = std::cos(angleDeg * M_PI / 180.0);
    mve::TriangleMesh::FaceList kept;
    kept.reserve(f.size());
    size_t removed = 0;
    for (size_t i = 0; i + 2 < f.size(); i += 3) {
        math::Vec3f const& a = v[f[i]];
        math::Vec3f const& b = v[f[i + 1]];
        math::Vec3f const& c = v[f[i + 2]];
        math::Vec3f n = (b - a).cross(c - a);
        float len = n.norm();
        bool low = a[2] < height && b[2] < height && c[2] < height;
        // Faces may be wound either way: "facing up" means the normal is close to the vertical.
        bool flat = len > 0.0f && std::fabs(n[2] / len) >= minUp;
        if (low && flat) { ++removed; continue; }
        kept.push_back(f[i]); kept.push_back(f[i + 1]); kept.push_back(f[i + 2]);
    }
    f.swap(kept);
    mve::geom::SavePLYOptions opts;   // keeps colours, confidences and values for meshclean
    try { mve::geom::save_ply_mesh(mesh, out, opts); } catch (std::exception const& e) {
        std::fprintf(stderr, "groundcut: cannot write %s: %s\n", out.c_str(), e.what()); return 1;
    }
    std::printf("groundcut: removed %zu of %zu faces (low and facing up, below z = %g)\n", removed, removed + f.size() / 3, height);
    return 0;
}
