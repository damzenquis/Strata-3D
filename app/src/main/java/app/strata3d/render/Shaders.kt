package app.strata3d.render

import android.opengl.GLES30
import android.util.Log

internal object Shaders {

    /** Mesh vertex shader. Barycentrics come from gl_VertexID so wireframe needs no extra attribute. */
    const val MESH_VERTEX = """#version 300 es
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec2 aUv;   // photo colours: texture coordinate (unused, and (0, 0), for plain models)
uniform mat4 uView;
uniform mat4 uMvp;
out vec3 vViewPos;
out vec3 vBary;
out vec2 vUv;
#ifdef SECTION
out vec3 vWorldPos;
#endif
void main() {
    vec4 p = vec4(aPosition, 1.0);
    vViewPos = (uView * p).xyz;
#ifdef SECTION
    vWorldPos = aPosition;
#endif
    int corner = gl_VertexID % 3;
    vBary = vec3(corner == 0 ? 1.0 : 0.0, corner == 1 ? 1.0 : 0.0, corner == 2 ? 1.0 : 0.0);
    vUv = vec2(aUv.x, 1.0 - aUv.y);   // OBJ's v runs up; image rows run down
    gl_Position = uMvp * p;
}
"""

    /**
     * Flat shading from screen-space derivatives + Blinn-Phong with a headlight and a fill light.
     * uMode: 0 = shaded, 1 = shaded + edges, 2 = wireframe only.
     */
    const val MESH_FRAGMENT = """#version 300 es
precision highp float;
in vec3 vViewPos;
in vec3 vBary;
in vec2 vUv;
uniform vec3 uColor;
uniform int uTextured;      // 1 = photo colours from uTex instead of uColor
uniform sampler2D uTex;
uniform vec3 uEdgeColor;
uniform int uMode;
uniform int uOrtho;        // 1 = orthographic projection: the view direction is constant
uniform float uEdgeScale;  // edge width multiplier (1 on screen; output scale for snapshots)
#ifdef SECTION
in vec3 vWorldPos;
uniform vec4 uSectionPlane; // keep where dot(plane, (p, 1)) >= 0 (see SectionPlane.equation)
#endif
out vec4 fragColor;

float edgeFactor() {
    vec3 d = fwidth(vBary) * uEdgeScale;
    vec3 a = smoothstep(vec3(0.0), d * 1.25, vBary);
    return 1.0 - min(min(a.x, a.y), a.z);
}

void main() {
#ifdef SECTION
    if (dot(uSectionPlane, vec4(vWorldPos, 1.0)) < 0.0) discard;
#endif
    vec3 n = normalize(cross(dFdx(vViewPos), dFdy(vViewPos)));
    vec3 v = uOrtho == 1 ? vec3(0.0, 0.0, 1.0) : normalize(-vViewPos);
    if (dot(n, v) < 0.0) n = -n;

    vec3 base = uColor;
    if (uTextured == 1) base = texture(uTex, vUv).rgb;
    else if (!gl_FrontFacing) base *= vec3(0.75, 0.55, 0.55); // hint at inside / inverted faces

    // Key light slightly above-left of the camera, fill light from below-right (view space).
    vec3 key = normalize(vec3(-0.35, 0.55, 1.0));
    vec3 fill = normalize(vec3(0.6, -0.4, 0.5));
    float diffuse = max(dot(n, key), 0.0) * 0.75 + max(dot(n, fill), 0.0) * 0.25;
    float spec = pow(max(dot(n, normalize(key + v)), 0.0), 48.0) * 0.25;
    float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * 0.12;
    vec3 color = base * (0.22 + diffuse) + vec3(spec + rim);
    // Photo colours already hold the real light: shade them only gently, without highlights.
    if (uTextured == 1) color = base * (0.62 + 0.45 * diffuse);

    if (uMode == 0) {
        fragColor = vec4(color, 1.0);
        return;
    }
    float e = edgeFactor();
    if (uMode == 2) {
        if (e < 0.05) discard;
        fragColor = vec4(uColor, e);
        return;
    }
    fragColor = vec4(mix(color, uEdgeColor, e * 0.85), 1.0);
}
"""

    /**
     * The section variant of a mesh shader: same source with `SECTION` defined. It is a separate program because a
     * shader that can `discard` may lose early depth testing on some GPUs, so normal viewing keeps the plain one.
     */
    fun withSection(source: String): String {
        val eol = source.indexOf('\n')
        return source.substring(0, eol + 1) + "#define SECTION\n" + source.substring(eol + 1)
    }

    /**
     * Section capping count pass: only positions and the plane test. Colour writes are off in this pass, so the
     * full lighting shader would be wasted work (it doubled the frame time of a capped 1M-triangle model).
     */
    const val SECTION_COUNT_VERTEX = """#version 300 es
layout(location = 0) in vec3 aPosition;
uniform mat4 uMvp;
out vec3 vWorldPos;
void main() {
    vWorldPos = aPosition;
    gl_Position = uMvp * vec4(aPosition, 1.0);
}
"""

    const val SECTION_COUNT_FRAGMENT = """#version 300 es
precision highp float;
in vec3 vWorldPos;
uniform vec4 uSectionPlane;
out vec4 fragColor;
void main() {
    if (dot(uSectionPlane, vec4(vWorldPos, 1.0)) < 0.0) discard;
    fragColor = vec4(0.0);
}
"""

    const val LINE_VERTEX = """#version 300 es
layout(location = 0) in vec3 aPosition;
uniform mat4 uMvp;
#ifdef SECTION
out vec3 vWorldPos;
#endif
void main() {
#ifdef SECTION
    vWorldPos = aPosition;
#endif
    gl_Position = uMvp * vec4(aPosition, 1.0);
}
"""

    /** Pre-transformed clip-space positions (snapshot grid quads built on the CPU). */
    const val CLIP_VERTEX = """#version 300 es
layout(location = 0) in vec4 aClip;
void main() {
    gl_Position = aClip;
}
"""

    const val LINE_FRAGMENT = """#version 300 es
#ifdef SECTION
precision highp float;
#else
precision mediump float;
#endif
uniform vec4 uColor;
#ifdef SECTION
in vec3 vWorldPos;
uniform vec4 uSectionPlane;
#endif
out vec4 fragColor;
void main() {
#ifdef SECTION
    if (dot(uSectionPlane, vec4(vWorldPos, 1.0)) < 0.0) discard;
#endif
    fragColor = uColor;
}
"""
}

internal object GlUtil {
    private const val TAG = "GlUtil"

    fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw IllegalStateException("Program link failed: $log")
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            Log.e(TAG, "Shader compile failed: $log\n$source")
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("Shader compile failed: $log")
        }
        return shader
    }
}
