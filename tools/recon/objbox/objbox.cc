/*
 * objbox: the object's box for the depth stage, from the SfM result, before depth maps are computed.
 *
 * Version 2 (default): an upright box standing on the supporting surface.
 *   1. "Up" in the SfM frame:
 *      - from gravity: each photo's phone orientation (Strata:3D capture format 2, --frames=frames.jsonl) gives the
 *        gravity direction in that camera, which the SfM rotation turns into the SfM frame; averaged over cameras;
 *      - otherwise from how the photos were held: the camera axis that is most consistent across cameras is the
 *        image's vertical (photos are taken upright); its sign makes the cameras look down on average. Reported as
 *        "up from photo orientation (assumed upright)".
 *   2. Look-at point p: the point closest to every camera's optical axis.
 *   3. Supporting surface: a RANSAC plane (tolerance 0.5 % of the camera distance, normal within 15 degrees of
 *      gravity up, or 25 of photo-orientation up)
 *      among the SfM points below p within twice the old box radius; its normal becomes the final "up".
 *   4. Box: horizontal radius and top from the SfM points standing on that surface near p (95th / 98th
 *      percentiles plus margins), capped at the old box; bottom just above the surface, so the surface is cut away
 *      and the object's base is not.
 *   5. Tap (--manifest=manifest.json, format 2): the user's tap on the object becomes a ray from the first photo's
 *      camera (rotated by the phone's orientation change between the tap and that photo). If it misses the box, the
 *      tool fails instead of guessing which object was meant.
 *   6. The scene is rotated and moved so up is +z (the Strata:3D viewer's and 3D printing's convention) and the
 *      object stands at the origin (cameras in meta.ini and the
 *      bundle's points and cameras), and the box is written in that frame. A marker file (aligned.txt) prevents a
 *      second rotation.
 *
 * Version 1 (--legacy, or when no up direction can be found): p ± ratio × median camera distance, axis-aligned in
 * the SfM frame, no rotation.
 *
 * Fails (exit 2, no box) when cameras are too few, the axes do not converge, or the look-at point is behind the
 * cameras; exit 3 when the tap misses the box.
 *
 * Usage: objbox [--legacy] [--ratio=0.35] [--frames=FILE] [--manifest=FILE] SCENE_DIR
 * Prints BOX_METHOD=... and BBOX=minx,miny,minz,maxx,maxy,maxz; writes SCENE_DIR/objbox.txt (the box) and
 * SCENE_DIR/objbox_report.txt.
 */
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#include "mve/bundle.h"
#include "mve/bundle_io.h"
#include "mve/scene.h"
#include "util/file_system.h"

namespace {

struct V {
    double x = 0, y = 0, z = 0;
    V() = default;
    V(double a, double b, double c) : x(a), y(b), z(c) {}
    V operator+(V o) const { return {x + o.x, y + o.y, z + o.z}; }
    V operator-(V o) const { return {x - o.x, y - o.y, z - o.z}; }
    V operator*(double s) const { return {x * s, y * s, z * s}; }
    double dot(V o) const { return x * o.x + y * o.y + z * o.z; }
    V cross(V o) const { return {y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x}; }
    double norm() const { return std::sqrt(dot(*this)); }
    V unit() const { double n = norm(); return n > 1e-12 ? *this * (1.0 / n) : *this; }
};

double angleDeg(V a, V b) { return std::acos(std::max(-1.0, std::min(1.0, a.unit().dot(b.unit())))) * 180.0 / M_PI; }

/** Quaternion (x, y, z, w) rotation of v. */
V qrot(const double* q, V v) {
    V u(q[0], q[1], q[2]);
    V c = u.cross(v);
    return v + c * (2 * q[3]) + u.cross(c) * 2;
}
V qrotInv(const double* q, V v) { double c[4] = {-q[0], -q[1], -q[2], q[3]}; return qrot(c, v); }

/** MVE camera: rot is world-to-camera (row-major), camera +x right, +y down, +z forward in the stored image. */
V camToWorld(const float* R, V v) {
    return {R[0] * v.x + R[3] * v.y + R[6] * v.z, R[1] * v.x + R[4] * v.y + R[7] * v.z, R[2] * v.x + R[5] * v.y + R[8] * v.z};
}
/** Android device axes (+x right, +y up the screen, +z out of the screen) to upright-image camera axes. */
V devToUpright(V d) { return {d.x, -d.y, -d.z}; }
/** Upright-image camera axes to the stored image's, when the stored image shows upright after r° clockwise. */
V uprightToStored(V u, int r) {
    switch (r) {
        case 90: return {u.y, -u.x, u.z};
        case 270: return {-u.y, u.x, u.z};
        case 180: return {-u.x, -u.y, u.z};
        default: return u;
    }
}

double percentile(std::vector<double> v, double p) {
    if (v.empty()) return 0;
    std::sort(v.begin(), v.end());
    size_t i = std::min(v.size() - 1, (size_t)std::floor(p * (v.size() - 1)));
    return v[i];
}

std::string readFile(const std::string& path) {
    std::ifstream f(path.c_str());
    std::stringstream ss; ss << f.rdbuf();
    return ss.str();
}

/** Numbers of the JSON array after "key":[ in s (from pos). */
bool jsonArray(const std::string& s, const std::string& key, size_t from, double* out, int n, size_t end = std::string::npos) {
    size_t k = s.find("\"" + key + "\":[", from);
    if (k == std::string::npos || (end != std::string::npos && k > end)) return false;
    const char* c = s.c_str() + k + key.size() + 4;
    for (int i = 0; i < n; ++i) {
        char* e; out[i] = std::strtod(c, &e);
        if (e == c) return false;
        c = e; while (*c == ',' || *c == ' ') ++c;
    }
    return true;
}
bool jsonNumber(const std::string& s, const std::string& key, size_t from, double* out, size_t end = std::string::npos) {
    size_t k = s.find("\"" + key + "\":", from);
    if (k == std::string::npos || (end != std::string::npos && k > end)) return false;
    const char* c = s.c_str() + k + key.size() + 3;
    char* e; *out = std::strtod(c, &e);
    return e != c;
}

struct Photo { double q[4]; int rotation = 0; bool hasQ = false; };

/** frames.jsonl (Strata:3D capture format 2): image base name ("000001") -> orientation and rotation. */
std::map<std::string, Photo> readFrames(const std::string& path) {
    std::map<std::string, Photo> out;
    std::ifstream f(path.c_str());
    std::string line;
    while (std::getline(f, line)) {
        size_t k = line.find("\"image\":\"");
        if (k == std::string::npos) continue;
        size_t a = k + 9, b = line.find('"', a);
        std::string img = line.substr(a, b - a);
        size_t slash = img.find_last_of('/'); if (slash != std::string::npos) img = img.substr(slash + 1);
        size_t dot = img.find_last_of('.'); if (dot != std::string::npos) img = img.substr(0, dot);
        Photo p;
        p.hasQ = jsonArray(line, "orientation", 0, p.q, 4);
        double r = 0; if (jsonNumber(line, "rotationDegrees", 0, &r)) p.rotation = (int)r;
        out[img] = p;
    }
    return out;
}

// Eigenvalues/eigenvectors of a symmetric 3x3 matrix (cyclic Jacobi). Columns of v are the eigenvectors.
void jacobi3(double a[3][3], double ev[3], double v[3][3]) {
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j) v[i][j] = (i == j) ? 1.0 : 0.0;
    for (int sweep = 0; sweep < 50; ++sweep) {
        double off = a[0][1] * a[0][1] + a[0][2] * a[0][2] + a[1][2] * a[1][2];
        if (off < 1e-24) break;
        for (int p = 0; p < 2; ++p) {
            for (int q = p + 1; q < 3; ++q) {
                if (std::fabs(a[p][q]) < 1e-30) continue;
                double theta = (a[q][q] - a[p][p]) / (2.0 * a[p][q]);
                double t = (theta >= 0 ? 1.0 : -1.0) / (std::fabs(theta) + std::sqrt(theta * theta + 1.0));
                double c = 1.0 / std::sqrt(t * t + 1.0), s = t * c;
                for (int k = 0; k < 3; ++k) {  // A <- A J
                    double akp = a[k][p], akq = a[k][q];
                    a[k][p] = c * akp - s * akq;
                    a[k][q] = s * akp + c * akq;
                }
                for (int k = 0; k < 3; ++k) {  // A <- J^T A
                    double apk = a[p][k], aqk = a[q][k];
                    a[p][k] = c * apk - s * aqk;
                    a[q][k] = s * apk + c * aqk;
                }
                for (int k = 0; k < 3; ++k) {
                    double vkp = v[k][p], vkq = v[k][q];
                    v[k][p] = c * vkp - s * vkq;
                    v[k][q] = s * vkp + c * vkq;
                }
            }
        }
    }
    for (int i = 0; i < 3; ++i) ev[i] = a[i][i];
}

struct Cam { std::string name; mve::View::Ptr view; V c, d; const float* R; };

int fail(const char* why, int code = 2) { std::fprintf(stderr, "objbox: %s\n", why); return code; }

}  // namespace

int main(int argc, char** argv) {
    double ratio = 0.35;
    bool legacy = false;
    std::string dir, framesPath, manifestPath;
    for (int i = 1; i < argc; ++i) {
        if (std::strncmp(argv[i], "--ratio=", 8) == 0) ratio = std::atof(argv[i] + 8);
        else if (std::strncmp(argv[i], "--frames=", 9) == 0) framesPath = argv[i] + 9;
        else if (std::strncmp(argv[i], "--manifest=", 11) == 0) manifestPath = argv[i] + 11;
        else if (std::strcmp(argv[i], "--legacy") == 0) legacy = true;
        else dir = argv[i];
    }
    if (dir.empty() || !(ratio > 0.0 && ratio < 2.0)) {
        std::fprintf(stderr, "usage: objbox [--legacy] [--ratio=0.35] [--frames=FILE] [--manifest=FILE] SCENE_DIR\n");
        return 1;
    }
    if (util::fs::file_exists((dir + "/aligned.txt").c_str()))
        return fail("scene already aligned by objbox (aligned.txt); run on a fresh SfM scene", 1);

    mve::Scene::Ptr scene;
    try { scene = mve::Scene::create(dir); } catch (std::exception const& e) {
        std::fprintf(stderr, "objbox: cannot load scene: %s\n", e.what()); return 1;
    }

    std::vector<Cam> cams;
    for (mve::View::Ptr const& view : scene->get_views()) {
        if (view == nullptr || !view->is_camera_valid()) continue;
        float c[3], d[3];
        view->get_camera().fill_camera_pos(c);
        view->get_camera().fill_viewing_direction(d);
        Cam k; k.name = view->get_name(); k.view = view; k.c = V(c[0], c[1], c[2]); k.d = V(d[0], d[1], d[2]).unit();
        k.R = view->get_camera().rot;
        cams.push_back(k);
    }
    const size_t n = cams.size();
    if (n < 3) return fail("fewer than 3 registered cameras");

    // ---- look-at point (least squares over the optical axes) ----
    double A[3][3] = {}, b[3] = {};
    for (Cam const& k : cams) {
        double dv[3] = {k.d.x, k.d.y, k.d.z}, cv[3] = {k.c.x, k.c.y, k.c.z};
        for (int r = 0; r < 3; ++r) for (int s = 0; s < 3; ++s) {
            double pij = (r == s ? 1.0 : 0.0) - dv[r] * dv[s];
            A[r][s] += pij; b[r] += pij * cv[s];
        }
    }
    // Spread of viewing directions: smallest eigenvalue of sum(I - d d^T) per camera (0.02 ~ +/- 8 degrees).
    double work[3][3], ev[3], evec[3][3];
    std::memcpy(work, A, sizeof(work));
    jacobi3(work, ev, evec);
    double spread = std::min(ev[0], std::min(ev[1], ev[2])) / double(n);
    if (spread < 0.02) return fail("camera axes do not converge on one object (capture went forward, not around)");
    double det = A[0][0] * (A[1][1] * A[2][2] - A[1][2] * A[2][1]) - A[0][1] * (A[1][0] * A[2][2] - A[1][2] * A[2][0])
               + A[0][2] * (A[1][0] * A[2][1] - A[1][1] * A[2][0]);
    auto solve = [&](int col) {
        double M[3][3]; std::memcpy(M, A, sizeof(M));
        for (int r = 0; r < 3; ++r) M[r][col] = b[r];
        return (M[0][0] * (M[1][1] * M[2][2] - M[1][2] * M[2][1]) - M[0][1] * (M[1][0] * M[2][2] - M[1][2] * M[2][0])
              + M[0][2] * (M[1][0] * M[2][1] - M[1][1] * M[2][0])) / det;
    };
    V p(solve(0), solve(1), solve(2));
    std::vector<double> dists; size_t front = 0;
    for (Cam const& k : cams) { V w = p - k.c; dists.push_back(w.norm()); if (w.dot(k.d) > 0) ++front; }
    double dist = percentile(dists, 0.5);
    if (double(front) / n < 0.9) return fail("look-at point is behind too many cameras");
    double r0 = ratio * dist;

    std::ostringstream report;
    report << "cameras " << n << ", look-at (" << p.x << ", " << p.y << ", " << p.z << "), median distance " << dist << "\n";

    // ---- up direction ----
    V up; std::string method;
    if (!legacy && !framesPath.empty()) {
        std::map<std::string, Photo> photos = readFrames(framesPath);
        std::vector<V> gs;
        for (Cam const& k : cams) {
            auto it = photos.find(k.name);
            if (it == photos.end() || !it->second.hasQ) continue;
            V gDev = qrotInv(it->second.q, V(0, 0, -1));
            gs.push_back(camToWorld(k.R, uprightToStored(devToUpright(gDev), it->second.rotation)).unit());
        }
        if (gs.size() >= 3) {
            V m; for (V g : gs) m = m + g;
            V down = m.unit();
            std::vector<double> dev; for (V g : gs) dev.push_back(angleDeg(g, down));
            double med = percentile(dev, 0.5), p90 = percentile(dev, 0.9);
            report << "gravity from " << gs.size() << " photos: spread median " << med << " deg, 90% " << p90 << " deg\n";
            if (med < 5.0) { up = down * -1.0; method = "gravity"; }
            else report << "gravity directions disagree; not used\n";
        }
    }
    if (!legacy && method.empty()) {
        // Photos held upright: the image's vertical is the camera axis most consistent across cameras.
        V sx, sy;
        for (Cam const& k : cams) { sx = sx + camToWorld(k.R, V(1, 0, 0)); sy = sy + camToWorld(k.R, V(0, 1, 0)); }
        double cx = sx.norm() / n, cy = sy.norm() / n;
        V axis = (cx > cy ? sx : sy).unit();
        double consistency = std::max(cx, cy);
        V fwd; for (Cam const& k : cams) fwd = fwd + k.d;
        if (fwd.dot(axis) > 0) axis = axis * -1.0;   // cameras look down on average
        report << "photo-orientation up: consistency " << consistency << "\n";
        if (consistency > 0.8) { up = axis; method = "photo orientation (assumed upright)"; }
    }

    // ---- legacy box ----
    auto writeBox = [&](V lo, V hi, const std::string& m) {
        char box[256];
        std::snprintf(box, sizeof(box), "%.4f,%.4f,%.4f,%.4f,%.4f,%.4f", lo.x, lo.y, lo.z, hi.x, hi.y, hi.z);
        std::ofstream f((dir + "/objbox.txt").c_str()); f << box << "\n";
        std::ofstream r((dir + "/objbox_report.txt").c_str()); r << report.str() << "method " << m << "\nbox " << box << "\n";
        std::fprintf(stderr, "%s", report.str().c_str());
        std::printf("BOX_METHOD=%s\nBBOX=%s\n", m.c_str(), box);
        return (f && r) ? 0 : fail("cannot write objbox.txt");
    };
    if (method.empty()) {
        report << (legacy ? "legacy box requested\n" : "no up direction found: legacy box (no surface, no rotation)\n");
        return writeBox(p - V(r0, r0, r0), p + V(r0, r0, r0), "legacy");
    }

    // ---- supporting surface and object extent from the SfM points ----
    mve::Bundle::Ptr bundle;
    try { bundle = mve::load_mve_bundle(dir + "/" + MVE_SCENE_BUNDLE_FILE); } catch (std::exception const& e) {
        std::fprintf(stderr, "objbox: cannot read the SfM points: %s\n", e.what()); return 1;
    }
    // Supporting surface: RANSAC plane among the SfM points below the look-at point within twice the old box radius
    // of its vertical (more of the surface is visible further out), with a normal within 15 degrees of gravity "up",
    // or 25 degrees of "up" from photo orientation. Its normal then replaces "up". On the A17, gravity and the desk
    // normal agreed to 0.6-1.8 degrees; "up" from photo orientation alone was 10-14 degrees off (phones aren't held
    // perfectly upright).
    const double maxTilt = (method == "gravity") ? 15.0 : 25.0;
    std::vector<V> cand;
    for (auto const& f : bundle->get_features()) {
        V w = V(f.pos[0], f.pos[1], f.pos[2]) - p;
        double h = w.dot(up);
        if (h < 0 && h > -2 * r0 && (w - up * h).norm() < 2 * r0) cand.push_back(w);
    }
    const double tol = 0.005 * dist;
    bool plane = false; double planeH = 0; V normal = up; size_t bestIn = 0;
    if (cand.size() >= 20) {
        unsigned seed = 12345;
        auto rnd = [&]() { seed = seed * 1103515245u + 12345u; return (seed >> 8) % cand.size(); };
        for (int it = 0; it < 4000; ++it) {
            V a = cand[rnd()], b2 = cand[rnd()], c = cand[rnd()];
            V nn = (b2 - a).cross(c - a);
            if (nn.norm() < 1e-12) continue;
            nn = nn.unit(); if (nn.dot(up) < 0) nn = nn * -1.0;
            if (angleDeg(nn, up) > maxTilt) continue;
            double d0 = nn.dot(a);
            size_t in = 0; for (V const& q : cand) if (std::fabs(nn.dot(q) - d0) < tol) ++in;
            if (in > bestIn) { bestIn = in; normal = nn; planeH = d0; }
        }
        plane = bestIn >= 30 && bestIn >= 0.05 * cand.size();
    }
    if (plane) {
        // Least-squares refit on the inliers: centroid, and the normal as the smallest-variance direction.
        V cen; std::vector<V> in;
        for (V const& q : cand) if (std::fabs(normal.dot(q) - planeH) < tol) { in.push_back(q); cen = cen + q; }
        cen = cen * (1.0 / in.size());
        double C[3][3] = {};
        for (V const& q : in) { V d = q - cen; double dv[3] = {d.x, d.y, d.z}; for (int r = 0; r < 3; ++r) for (int c = 0; c < 3; ++c) C[r][c] += dv[r] * dv[c]; }
        double ev2[3], vec[3][3]; jacobi3(C, ev2, vec);
        int k = 0; for (int i = 1; i < 3; ++i) if (ev2[i] < ev2[k]) k = i;
        V nn(vec[0][k], vec[1][k], vec[2][k]); nn = nn.unit(); if (nn.dot(up) < 0) nn = nn * -1.0;
        report << "surface: " << in.size() << " of " << cand.size() << " points below the look-at point; its normal is "
               << angleDeg(nn, up) << " deg from the " << method << " up\n";
        up = nn;
        planeH = up.dot(cen);
        report << "surface " << -planeH / dist << " x distance below the look-at point\n";
    } else {
        report << "no supporting surface found (best plane " << bestIn << " of " << cand.size() << " points)\n";
    }
    V e1 = up.cross(std::fabs(up.x) < 0.9 ? V(1, 0, 0) : V(0, 1, 0)).unit();
    V e2 = e1.cross(up);   // (e1, up, e2) is right-handed
    struct Pt { double h, rad; };
    std::vector<Pt> near;
    for (auto const& f : bundle->get_features()) {
        V w = V(f.pos[0], f.pos[1], f.pos[2]) - p;
        double h = w.dot(up);
        double rad = (w - up * h).norm();
        if (rad < r0) near.push_back({h, rad});
    }
    const double bin = tol;
    double base = plane ? planeH + 0.003 * dist : -r0;
    std::vector<double> rads, hs;
    for (Pt const& q : near) if (q.h > base + 2 * bin && q.h < base + 3 * r0) { rads.push_back(q.rad); hs.push_back(q.h); }
    double radius = r0, top = r0;
    if (rads.size() >= 30) {
        radius = std::min(r0, std::max(0.08 * dist, 1.15 * percentile(rads, 0.95)));
        // Height only from points inside that radius: a wall or furniture behind the object stays out.
        std::vector<double> hin;
        for (size_t i = 0; i < rads.size(); ++i) if (rads[i] <= radius) hin.push_back(hs[i]);
        // No "stop at the first empty height gap" rule: on the A17 bottle the gap was the plain glass neck (no SfM
        // points) and the points above it were the cap. Clipping the object is worse than including clutter, and
        // plain sections inside objects are common (necks, legs, vase bodies).
        top = std::min(base + 3 * r0, std::max(0.05 * dist, percentile(hin, 0.98) + 0.03 * dist));
        report << "object points: " << rads.size() << "\n";
    } else {
        report << "few object points (" << rads.size() << "): old box size kept\n";
    }

    // ---- tap ----
    if (!manifestPath.empty()) {
        std::string m = readFile(manifestPath);
        size_t t = m.find("\"objectTap\":{");
        double u, v, qt[4];
        if (t != std::string::npos && jsonNumber(m, "u", t, &u) && jsonNumber(m, "v", t, &v) && jsonArray(m, "orientation", t, qt, 4, m.find('}', t))) {
            std::map<std::string, Photo> photos = readFrames(framesPath);
            const Cam* first = nullptr;
            for (Cam const& k : cams) if (k.name == "000001") first = &k;
            auto it = photos.find("000001");
            if (first == nullptr || it == photos.end() || !it->second.hasQ) {
                report << "tap: first photo not registered, tap not checked\n";
            } else {
                int r = it->second.rotation;
                mve::CameraInfo const& ci = first->view->get_camera();
                // Upright image aspect: stored is landscape on phones; upright swaps it for quarter turns.
                double aspect = 4.0 / 3.0;  // width / height of the stored photo (4:3 capture)
                double uw = (r == 90 || r == 270) ? 1.0 : aspect, uh = (r == 90 || r == 270) ? aspect : 1.0;
                double f = ci.flen * std::max(uw, uh);   // MVE focal is relative to the larger side
                V rayU((u - 0.5) * uw / f, (v - 0.5) * uh / f, 1.0);
                V dev = devToUpright(rayU);              // the same sign flips map back
                V world = qrot(qt, dev);                  // sensor world at the tap
                V atPhoto = devToUpright(qrotInv(it->second.q, world));
                V dir = camToWorld(first->R, uprightToStored(atPhoto, r)).unit();
                V o = first->c - p;
                // Closest approach (t > 0) of the ray to the box's vertical axis, horizontally.
                V oh = o - up * o.dot(up), dh = dir - up * dir.dot(up);
                double tt = dh.dot(dh) > 1e-12 ? std::max(0.0, -oh.dot(dh) / dh.dot(dh)) : 0.0;
                double miss = (oh + dh * tt).norm();
                report << "tap ray passes " << miss / radius << " x the box radius from its axis\n";
                if (miss > 1.2 * radius) {
                    std::fprintf(stderr, "%s", report.str().c_str());
                    return fail("the tapped point is outside the object box: the cameras aimed at something else", 3);
                }
            }
        } else {
            report << "tap: none in the manifest\n";
        }
    }

    // ---- align the scene: up = +z (the viewer is Z-up), origin on the surface below the look-at point ----
    V o = p + up * (plane ? planeH : 0.0);
    // Rows: aligned x = e1, y = up x e1, z = up (right-handed: x cross y = z).
    V ey = up.cross(e1);
    double Q[3][3] = {{e1.x, e1.y, e1.z}, {ey.x, ey.y, ey.z}, {up.x, up.y, up.z}};
    auto toAligned = [&](V X) { V w = X - o; return V(Q[0][0] * w.x + Q[0][1] * w.y + Q[0][2] * w.z,
                                                    Q[1][0] * w.x + Q[1][1] * w.y + Q[1][2] * w.z,
                                                    Q[2][0] * w.x + Q[2][1] * w.y + Q[2][2] * w.z); };
    auto alignCamera = [&](mve::CameraInfo& c) {
        // x = R X + t with X = Q^T X' + o  =>  R' = R Q^T, t' = R o + t.
        float R2[9], t2[3];
        for (int i = 0; i < 3; ++i) {
            for (int j = 0; j < 3; ++j) R2[i * 3 + j] = float(c.rot[i * 3 + 0] * Q[j][0] + c.rot[i * 3 + 1] * Q[j][1] + c.rot[i * 3 + 2] * Q[j][2]);
            t2[i] = float(c.rot[i * 3 + 0] * o.x + c.rot[i * 3 + 1] * o.y + c.rot[i * 3 + 2] * o.z + c.trans[i]);
        }
        std::copy(R2, R2 + 9, c.rot); std::copy(t2, t2 + 3, c.trans);
    };
    for (Cam& k : cams) {
        mve::CameraInfo c = k.view->get_camera();
        alignCamera(c);
        k.view->set_camera(c);
        k.view->save_view();
    }
    for (auto& f : bundle->get_features()) {
        V a = toAligned(V(f.pos[0], f.pos[1], f.pos[2]));
        f.pos[0] = float(a.x); f.pos[1] = float(a.y); f.pos[2] = float(a.z);
    }
    for (auto& c : bundle->get_cameras()) if (c.flen != 0.0f) alignCamera(c);
    mve::save_mve_bundle(bundle, dir + "/" + MVE_SCENE_BUNDLE_FILE);
    {
        std::ofstream a((dir + "/aligned.txt").c_str());
        a << "origin " << o.x << " " << o.y << " " << o.z << "\nQ";
        for (int i = 0; i < 3; ++i) for (int j = 0; j < 3; ++j) a << " " << Q[i][j];
        a << "\n";
    }
    double bottom = plane ? 0.003 * dist : -r0;
    double topY = plane ? top - planeH : top;
    report << "up from " << method << "; box radius " << radius / dist << " x distance, height " << (topY - bottom) / dist << " x distance\n";
    return writeBox(V(-radius, -radius, bottom), V(radius, radius, topY), "v2 (" + method + (plane ? ", on surface" : ", no surface") + ")");
}
