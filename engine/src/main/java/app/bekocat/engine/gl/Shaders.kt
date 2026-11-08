package app.bekocat.engine.gl

/** All GLSL used by the compositor. Every image is premultiplied RGBA in output space unless stated. */
object Shaders {

    const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""

    /** Copies an external OES frame (decoder output) into a 2D texture, applying the SurfaceTexture matrix. */
    const val COPY_OES = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
uniform mat4 uTexMatrix;
uniform vec2 uSize;
out vec4 o;
void main() {
    vec2 uv = gl_FragCoord.xy / uSize;            // FBO space (y up) == GL texcoords
    vec2 st = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
    o = vec4(texture(uTex, st).rgb, 1.0);
}
"""

    private const val COMMON = """
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
vec3 rgb2hsl(vec3 c) {
    float mx = max(max(c.r, c.g), c.b), mn = min(min(c.r, c.g), c.b);
    float l = (mx + mn) * 0.5; float h = 0.0, s = 0.0; float d = mx - mn;
    if (d > 1e-5) {
        s = l > 0.5 ? d / (2.0 - mx - mn) : d / (mx + mn);
        if (mx == c.r) h = (c.g - c.b) / d + (c.g < c.b ? 6.0 : 0.0);
        else if (mx == c.g) h = (c.b - c.r) / d + 2.0;
        else h = (c.r - c.g) / d + 4.0;
        h /= 6.0;
    }
    return vec3(h, s, l);
}
float hue2rgb(float p, float q, float t) {
    if (t < 0.0) t += 1.0; if (t > 1.0) t -= 1.0;
    if (t < 1.0/6.0) return p + (q - p) * 6.0 * t;
    if (t < 0.5) return q;
    if (t < 2.0/3.0) return p + (q - p) * (2.0/3.0 - t) * 6.0;
    return p;
}
vec3 hsl2rgb(vec3 h) {
    if (h.y < 1e-5) return vec3(h.z);
    float q = h.z < 0.5 ? h.z * (1.0 + h.y) : h.z + h.y - h.z * h.y;
    float p = 2.0 * h.z - q;
    return vec3(hue2rgb(p, q, h.x + 1.0/3.0), hue2rgb(p, q, h.x), hue2rgb(p, q, h.x - 1.0/3.0));
}
float hash12(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
float vnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p); vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i), hash12(i + vec2(1,0)), u.x), mix(hash12(i + vec2(0,1)), hash12(i + vec2(1,1)), u.x), u.y);
}
float fbm(vec2 p) { float s = 0.0, a = 0.5; for (int i = 0; i < 5; i++) { s += a * vnoise(p); p *= 2.02; a *= 0.5; } return s; }
"""

    /**
     * Layer shader: samples a 2D source through inverse homographies (motion-blur samples),
     * applies crop, full colour pipeline, chroma/luma key, masks, AI matte and track matte.
     */
    val LAYER = """#version 300 es
precision highp float;
precision mediump sampler3D;
$COMMON
uniform sampler2D uTex;
uniform mat3 uTexM;          // uv(v down) → texture coords
uniform vec2 uOut;
uniform int uSamples;
uniform mat3 uInv[16];
uniform vec4 uCrop;           // l, t, r, b (uv)
uniform float uOpacity;
uniform int uUnpremul;
uniform float uExposure, uContrast, uHighlights, uShadows, uWhites, uBlacks, uSat, uVib, uTemp, uTint;
uniform vec3 uLift, uGamma, uGain;
uniform int uUseCurves; uniform sampler2D uCurves;
uniform int uUseHsl; uniform float uHslH[8]; uniform float uHslS[8]; uniform float uHslL[8];
uniform int uUseLut; uniform sampler3D uLut; uniform float uLutMix;
uniform int uKey; uniform vec3 uKeyColor; uniform float uSim, uSmooth, uSpill, uChoke, uShadow; uniform int uKeyView; uniform vec3 uDespill;
uniform float uLumaThr, uLumaSoft, uLumaInv;
uniform int uUseMask; uniform sampler2D uMask;
uniform int uUseAi; uniform sampler2D uAi; uniform float uAiSoft, uAiChoke; uniform int uAiView;
uniform int uMatteType; uniform sampler2D uMatte;
uniform int uUsePatch; uniform sampler2D uPatch;
uniform int uGrade;
out vec4 o;

vec3 grade(vec3 c) {
    // Exposure + white balance in linear light.
    vec3 lin = pow(max(c, 0.0), vec3(2.2));
    lin *= exp2(uExposure);
    lin *= vec3(1.0 + uTemp * 0.18 + uTint * 0.05, 1.0 - uTint * 0.12, 1.0 - uTemp * 0.18 + uTint * 0.05);
    c = pow(max(lin, 0.0), vec3(1.0 / 2.2));
    float l = luma(c);
    // Highlights / shadows: luma-weighted lifts.
    float hw = smoothstep(0.45, 1.0, l), sw = 1.0 - smoothstep(0.0, 0.55, l);
    c += uHighlights * 0.35 * hw * c + uShadows * 0.35 * sw * (1.0 - c) * c * 2.0;
    // Whites / blacks: level points.
    float bp = -uBlacks * 0.15, wp = 1.0 - uWhites * 0.15;
    c = (c - bp) / max(wp - bp, 1e-3);
    // Contrast around mid-grey (S-curve when positive).
    c = mix(vec3(0.42), c, 1.0 + uContrast);
    // Lift / gamma / gain (per channel).
    c = c * (1.0 + uGain) + uLift * (1.0 - c);
    c = pow(max(c, 0.0), 1.0 / max(1.0 + uGamma, vec3(0.05)));
    c = clamp(c, 0.0, 1.0);
    if (uUseCurves == 1) {
        c = vec3(texture(uCurves, vec2(c.r, 0.5)).r, texture(uCurves, vec2(c.g, 0.5)).g, texture(uCurves, vec2(c.b, 0.5)).b);
        c = vec3(texture(uCurves, vec2(c.r, 0.5)).a, texture(uCurves, vec2(c.g, 0.5)).a, texture(uCurves, vec2(c.b, 0.5)).a);
    }
    if (uUseHsl == 1) {
        vec3 h = rgb2hsl(c);
        float dh = 0.0, ds = 0.0, dl = 0.0, wsum = 0.0;
        for (int i = 0; i < 8; i++) {
            float centre = float(i) / 8.0;
            float d = abs(h.x - centre); d = min(d, 1.0 - d);
            float w = max(0.0, 1.0 - d * 8.0) * smoothstep(0.02, 0.15, h.y);
            dh += w * uHslH[i]; ds += w * uHslS[i]; dl += w * uHslL[i]; wsum += w;
        }
        h.x = fract(h.x + dh / 360.0);
        h.y = clamp(h.y * (1.0 + ds), 0.0, 1.0);
        h.z = clamp(h.z + dl * 0.25 * h.y, 0.0, 1.0);
        c = hsl2rgb(h);
    }
    float L = luma(c);
    float sat = 1.0 + uSat;
    float mx = max(max(c.r, c.g), c.b), mn = min(min(c.r, c.g), c.b);
    float vib = uVib * (1.0 - (mx - mn));
    c = mix(vec3(L), c, sat + vib);
    c = clamp(c, 0.0, 1.0);
    if (uUseLut == 1) c = mix(c, texture(uLut, c).rgb, uLutMix);
    return c;
}

float keyAlpha(inout vec3 c) {
    if (uKey == 1) {
        // Chroma distance in YCbCr.
        vec3 yk = vec3(luma(uKeyColor), uKeyColor.b - luma(uKeyColor), uKeyColor.r - luma(uKeyColor));
        vec3 yc = vec3(luma(c), c.b - luma(c), c.r - luma(c));
        float d = distance(yc.yz, yk.yz);
        float a = smoothstep(uSim * 0.5, uSim * 0.5 + max(uSmooth, 1e-3), d);
        a = clamp(a + uChoke * 0.5 * a * (1.0 - a) * 4.0, 0.0, 1.0);
        // Spill suppression toward the despill colour.
        float spill = clamp(dot(c - vec3(luma(c)), normalize(uKeyColor - vec3(luma(uKeyColor)) + 1e-5)), 0.0, 1.0) * uSpill;
        c = mix(c, c - spill * (uKeyColor - uDespill) * 0.6, 1.0);
        c = clamp(c, 0.0, 1.0);
        // Shadow preservation keeps dark key-coloured areas partially opaque.
        float shadow = (1.0 - smoothstep(0.0, luma(uKeyColor), luma(c))) * uShadow;
        return max(a, shadow);
    }
    if (uKey == 2) {
        float a = smoothstep(uLumaThr, uLumaThr + max(uLumaSoft, 1e-3), luma(c));
        return uLumaInv > 0.5 ? 1.0 - a : a;
    }
    return 1.0;
}

void main() {
    vec2 px = vec2(gl_FragCoord.x, uOut.y - gl_FragCoord.y);
    vec4 acc = vec4(0.0);
    int n = max(uSamples, 1);
    for (int k = 0; k < 16; k++) {
        if (k >= n) break;
        vec3 h = uInv[k] * vec3(px, 1.0);
        vec2 uv = h.xy / h.z;
        if (h.z <= 0.0 || uv.x < uCrop.x || uv.y < uCrop.y || uv.x > 1.0 - uCrop.z || uv.y > 1.0 - uCrop.w) continue;
        vec2 st = (uTexM * vec3(uv, 1.0)).xy;
        vec4 s = texture(uTex, st);
        vec3 c = s.a > 1e-4 && uUnpremul == 1 ? s.rgb / s.a : s.rgb;
        float a = s.a;
        if (uUsePatch == 1) { vec4 pt = texture(uPatch, uv); c = mix(c, pt.a > 1e-4 ? pt.rgb / pt.a : c, pt.a); }
        if (uGrade == 1) c = grade(c);
        float ka = keyAlpha(c);
        if (uKey != 0 && uKeyView == 1) { c = vec3(ka); ka = 1.0; }
        a *= ka;
        if (uUseMask == 1) a *= texture(uMask, uv).a;
        if (uUseAi == 1) {
            float m = texture(uAi, uv).r;
            m = smoothstep(0.5 - uAiSoft * 0.5 + uAiChoke * 0.3, 0.5 + uAiSoft * 0.5 + uAiChoke * 0.3, m);
            if (uAiView == 1) { c = vec3(m); m = 1.0; }
            a *= m;
        }
        acc += vec4(c * a, a);
    }
    acc /= float(n);
    if (uMatteType > 0) {
        vec4 m = texture(uMatte, gl_FragCoord.xy / uOut);
        float mv = uMatteType == 1 ? m.a : uMatteType == 2 ? 1.0 - m.a : uMatteType == 3 ? luma(m.a > 0.0 ? m.rgb / m.a : vec3(0.0)) * m.a : 1.0 - luma(m.a > 0.0 ? m.rgb / m.a : vec3(0.0)) * m.a;
        acc *= mv;
    }
    o = acc * uOpacity;
}
"""

    /** W3C compositing & blending (premultiplied in/out). */
    val BLEND = """#version 300 es
precision highp float;
$COMMON
uniform sampler2D uBack, uSrc;
uniform vec2 uOut;
uniform int uMode;
out vec4 o;
float lum(vec3 c) { return dot(c, vec3(0.3, 0.59, 0.11)); }
vec3 clipColor(vec3 c) { float l = lum(c); float n = min(min(c.r, c.g), c.b); float x = max(max(c.r, c.g), c.b);
    if (n < 0.0) c = l + (c - l) * l / max(l - n, 1e-5); if (x > 1.0) c = l + (c - l) * (1.0 - l) / max(x - l, 1e-5); return c; }
vec3 setLum(vec3 c, float l) { return clipColor(c + (l - lum(c))); }
float sat(vec3 c) { return max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b); }
vec3 setSat(vec3 c, float s) { float mx = max(max(c.r, c.g), c.b), mn = min(min(c.r, c.g), c.b);
    return mx > mn ? (c - mn) * s / (mx - mn) : vec3(0.0); }
float softLight(float b, float s) { float d = b <= 0.25 ? ((16.0 * b - 12.0) * b + 4.0) * b : sqrt(b);
    return s <= 0.5 ? b - (1.0 - 2.0 * s) * b * (1.0 - b) : b + (2.0 * s - 1.0) * (d - b); }
float dodge(float b, float s) { return b == 0.0 ? 0.0 : s >= 1.0 ? 1.0 : min(1.0, b / (1.0 - s)); }
float burn(float b, float s) { return b >= 1.0 ? 1.0 : s <= 0.0 ? 0.0 : 1.0 - min(1.0, (1.0 - b) / s); }
vec3 blendF(vec3 b, vec3 s) {
    if (uMode == 1) return b * s;
    if (uMode == 2) return b + s - b * s;
    if (uMode == 3) return vec3(b.r <= 0.5 ? 2.0*b.r*s.r : 1.0-2.0*(1.0-b.r)*(1.0-s.r), b.g <= 0.5 ? 2.0*b.g*s.g : 1.0-2.0*(1.0-b.g)*(1.0-s.g), b.b <= 0.5 ? 2.0*b.b*s.b : 1.0-2.0*(1.0-b.b)*(1.0-s.b));
    if (uMode == 4) return vec3(softLight(b.r, s.r), softLight(b.g, s.g), softLight(b.b, s.b));
    if (uMode == 5) return vec3(s.r <= 0.5 ? 2.0*b.r*s.r : 1.0-2.0*(1.0-b.r)*(1.0-s.r), s.g <= 0.5 ? 2.0*b.g*s.g : 1.0-2.0*(1.0-b.g)*(1.0-s.g), s.b <= 0.5 ? 2.0*b.b*s.b : 1.0-2.0*(1.0-b.b)*(1.0-s.b));
    if (uMode == 6) return min(b, s);
    if (uMode == 7) return max(b, s);
    if (uMode == 8) return min(b + s, vec3(1.0));
    if (uMode == 9) return abs(b - s);
    if (uMode == 10) return b + s - 2.0 * b * s;
    if (uMode == 11) return vec3(dodge(b.r, s.r), dodge(b.g, s.g), dodge(b.b, s.b));
    if (uMode == 12) return vec3(burn(b.r, s.r), burn(b.g, s.g), burn(b.b, s.b));
    if (uMode == 13) return setLum(setSat(s, sat(b)), lum(b));
    if (uMode == 14) return setLum(setSat(b, sat(s)), lum(b));
    if (uMode == 15) return setLum(s, lum(b));
    if (uMode == 16) return setLum(b, lum(s));
    return s;
}
void main() {
    vec2 uv = gl_FragCoord.xy / uOut;
    vec4 B = texture(uBack, uv), S = texture(uSrc, uv);
    vec3 cb = B.a > 0.0 ? B.rgb / B.a : vec3(0.0);
    vec3 cs = S.a > 0.0 ? S.rgb / S.a : vec3(0.0);
    vec3 mixed = (1.0 - B.a) * cs + B.a * clamp(blendF(cb, cs), 0.0, 1.0);
    vec3 co = S.a * mixed + (1.0 - S.a) * B.rgb;
    o = vec4(co, S.a + B.a * (1.0 - S.a));
}
"""

    /** Transitions between two premultiplied full-frame images A (outgoing) and B (incoming). */
    val TRANSITION = """#version 300 es
precision highp float;
$COMMON
uniform sampler2D uA, uB, uMaskTex;
uniform vec2 uOut;
uniform float uP, uIntensity, uDir, uSeed;
uniform int uKind, uUseMaskTex;
out vec4 o;
vec4 A(vec2 uv) { return (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) ? vec4(0.0) : texture(uA, uv); }
vec4 B(vec2 uv) { return (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) ? vec4(0.0) : texture(uB, uv); }
vec4 blurA(vec2 uv, float r) { vec4 s = vec4(0.0); for (int i = -3; i <= 3; i++) for (int j = -3; j <= 3; j++) s += A(uv + vec2(i, j) * r / uOut); return s / 49.0; }
vec4 blurB(vec2 uv, float r) { vec4 s = vec4(0.0); for (int i = -3; i <= 3; i++) for (int j = -3; j <= 3; j++) s += B(uv + vec2(i, j) * r / uOut); return s / 49.0; }
vec4 dirBlurA(vec2 uv, vec2 d) { vec4 s = vec4(0.0); for (int i = 0; i < 12; i++) s += A(uv + d * (float(i) / 11.0 - 0.5)); return s / 12.0; }
vec4 dirBlurB(vec2 uv, vec2 d) { vec4 s = vec4(0.0); for (int i = 0; i < 12; i++) s += B(uv + d * (float(i) / 11.0 - 0.5)); return s / 12.0; }
vec4 zoomA(vec2 uv, float s) { return A((uv - 0.5) / s + 0.5); }
vec4 zoomB(vec2 uv, float s) { return B((uv - 0.5) / s + 0.5); }
void main() {
    vec2 uv = gl_FragCoord.xy / uOut;
    float p = clamp(uP, 0.0, 1.0);
    float k = uIntensity;
    vec2 asp = vec2(uOut.x / uOut.y, 1.0);
    vec4 a = A(uv), b = B(uv);
    vec4 r = mix(a, b, p);
    // 0 DISSOLVE
    if (uKind == 1 || uKind == 2) { // DIP black / white
        vec4 col = uKind == 1 ? vec4(0.0, 0.0, 0.0, 1.0) : vec4(1.0);
        r = p < 0.5 ? mix(a, col, p * 2.0) : mix(col, b, (p - 0.5) * 2.0);
    }
    if (uKind == 3) { // LUMA_FADE
        float l = uUseMaskTex == 1 ? texture(uMaskTex, vec2(uv.x, 1.0 - uv.y)).r : luma(a.rgb);
        r = mix(a, b, smoothstep(l - 0.1, l + 0.1, p * 1.2 - 0.1));
    }
    if (uKind >= 4 && uKind <= 7) { // WIPES L R U D
        float e = uKind == 4 ? 1.0 - uv.x : uKind == 5 ? uv.x : uKind == 6 ? uv.y : 1.0 - uv.y;
        float soft = 0.02 + 0.08 * (1.0 - k);
        r = mix(a, b, smoothstep(e - soft, e + soft, p * (1.0 + 2.0 * soft) - soft));
    }
    if (uKind == 8 || uKind == 9) { // SLIDE (B slides over A)
        float s = uKind == 8 ? 1.0 : -1.0;
        vec4 bb = B(uv + vec2(s * (1.0 - p), 0.0));
        r = bb + a * (1.0 - bb.a);
    }
    if (uKind == 10 || uKind == 11) { // PUSH
        vec2 d = uKind == 10 ? vec2(1.0, 0.0) : vec2(0.0, -1.0);
        vec4 aa = A(uv - d * p); vec4 bb = B(uv + d * (1.0 - p));
        r = bb + aa * (1.0 - bb.a);
    }
    if (uKind == 12) { // WHIP_PAN
        float m = sin(p * 3.14159);
        vec2 d = vec2(0.35 * m * k, 0.0);
        r = p < 0.5 ? dirBlurA(uv - vec2(p, 0.0), d) : dirBlurB(uv + vec2(1.0 - p, 0.0), d);
    }
    if (uKind == 13 || uKind == 15 || uKind == 35) { // ZOOM_IN / MATCH_ZOOM / SEAMLESS_LOOP
        float m = sin(p * 3.14159);
        r = p < 0.5 ? zoomA(uv, 1.0 + p * 2.0 * k) : zoomB(uv, 1.0 + (1.0 - p) * 2.0 * k * 0.5);
        r = mix(r, p < 0.5 ? blurA((uv - 0.5) / (1.0 + p) + 0.5, 4.0 * m) : blurB(uv, 4.0 * m), m * 0.5);
    }
    if (uKind == 14) { // ZOOM_OUT
        r = p < 0.5 ? zoomA(uv, 1.0 - p * 0.8) : zoomB(uv, 0.6 + (p - 0.5) * 0.8);
    }
    if (uKind == 16) { // SPIN
        float ang = (p < 0.5 ? p : p - 1.0) * 3.14159 * k;
        vec2 q = (uv - 0.5) * asp; q = mat2(cos(ang), -sin(ang), sin(ang), cos(ang)) * q; q /= asp; q += 0.5;
        float s = 1.0 + sin(p * 3.14159) * 0.5;
        q = (q - 0.5) * s + 0.5;
        r = p < 0.5 ? A(q) : B(q);
    }
    if (uKind == 17) { // PERSPECTIVE (cube rotate)
        float ang = p * 1.5708;
        if (uv.x < 1.0 - p) { float x = uv.x / (1.0 - p); float z = 1.0 + 0.5 * sin(ang) * x; r = A(vec2(x, (uv.y - 0.5) * z + 0.5)); }
        else { float x = (uv.x - (1.0 - p)) / p; float z = 1.0 + 0.5 * cos(ang) * (1.0 - x); r = B(vec2(x, (uv.y - 0.5) * z + 0.5)); }
    }
    if (uKind == 18) { // BLUR
        float m = sin(p * 3.14159) * 10.0 * k;
        r = mix(blurA(uv, m), blurB(uv, m), p);
    }
    if (uKind == 19 || uKind == 20) { // LIGHT_LEAK / FILM_BURN
        float n = fbm(uv * 3.0 + vec2(uSeed, p * 2.0));
        vec3 leak = uKind == 19 ? vec3(1.0, 0.55, 0.2) : vec3(1.0, 0.35, 0.05);
        float burn = smoothstep(0.2, 0.9, n + p * 1.4 - 0.5) * sin(p * 3.14159) * k;
        r = mix(a, b, smoothstep(0.35, 0.65, p + (n - 0.5) * 0.3));
        r.rgb += leak * burn * (uKind == 20 ? 1.6 : 1.0);
    }
    if (uKind == 21 || uKind == 22) { // GLITCH / RGB_SPLIT
        float m = sin(p * 3.14159) * k;
        float band = floor(uv.y * 24.0);
        float off = (hash12(vec2(band, floor(p * 20.0) + uSeed)) - 0.5) * 0.15 * m * (uKind == 21 ? 1.0 : 0.0);
        vec2 q = uv + vec2(off, 0.0);
        float sp = 0.03 * m;
        vec4 src0 = p < 0.5 ? A(q) : B(q);
        float rr = (p < 0.5 ? A(q + vec2(sp, 0.0)) : B(q + vec2(sp, 0.0))).r;
        float bb = (p < 0.5 ? A(q - vec2(sp, 0.0)) : B(q - vec2(sp, 0.0))).b;
        r = vec4(rr, src0.g, bb, src0.a);
    }
    if (uKind == 23 || uKind == 31) { // DISPLACEMENT / SMOOTH_WARP
        float m = sin(p * 3.14159) * k;
        vec2 d = (vec2(fbm(uv * 4.0 + uSeed), fbm(uv * 4.0 + 7.3 + uSeed)) - 0.5) * (uKind == 23 ? 0.25 : 0.12) * m;
        r = mix(A(uv + d), B(uv - d), smoothstep(0.3, 0.7, p));
    }
    if (uKind == 24) { // SHAPE_CIRCLE
        float d = length((uv - 0.5) * asp);
        r = mix(a, b, smoothstep(p * 1.2 - 0.02, p * 1.2 + 0.02, 1.2 - d - (1.2 - p * 1.2) * 2.0 + p * 1.2) );
        r = d < p * 1.2 ? b : a;
        float e = smoothstep(p * 1.2 - 0.01, p * 1.2 + 0.01, d); r = mix(b, a, e);
    }
    if (uKind == 25) { // SHAPE_DIAMOND
        float d = abs(uv.x - 0.5) * asp.x + abs(uv.y - 0.5);
        r = mix(b, a, smoothstep(p * 1.6 - 0.01, p * 1.6 + 0.01, d));
    }
    if (uKind == 26) { // CAMERA_SHAKE
        float m = sin(p * 3.14159) * 0.03 * k;
        vec2 sh = vec2(vnoise(vec2(p * 40.0, uSeed)) - 0.5, vnoise(vec2(uSeed, p * 40.0)) - 0.5) * m * 2.0;
        r = mix(A(uv + sh), B(uv + sh), smoothstep(0.4, 0.6, p));
    }
    if (uKind == 27) { // PAGE_TURN
        float edge = 1.0 - p * 1.2 + uv.y * 0.2;
        if (uv.x > edge) { float fold = uv.x - edge; vec2 q = vec2(edge - fold, uv.y); r = q.x > 0.0 ? A(q) * 0.75 : b; r = b * (1.0 - r.a) + r; }
        else r = a;
    }
    if (uKind == 28) { // STRETCH
        float m = sin(p * 3.14159);
        vec2 q = vec2((uv.x - 0.5) / (1.0 + 3.0 * m * k) + 0.5, uv.y);
        r = mix(A(q), B(q), smoothstep(0.4, 0.6, p));
    }
    if (uKind == 29) { // MOSAIC
        float m = sin(p * 3.14159);
        float cell = max(1.0, m * 60.0 * k);
        vec2 q = (floor(uv * uOut / cell) + 0.5) * cell / uOut;
        r = mix(A(q), B(q), smoothstep(0.35, 0.65, p));
    }
    if (uKind == 30 || uKind == 34) { // FLASH / BEAT_CUT
        float f = exp(-abs(p - 0.5) * (uKind == 30 ? 10.0 : 20.0)) * k;
        r = (p < 0.5 ? a : b);
        if (uKind == 34) r = p < 0.5 ? zoomA(uv, 1.0 + f * 0.15) : zoomB(uv, 1.0 + f * 0.15);
        r.rgb += vec3(f);
    }
    if (uKind == 32) { // RADIAL
        vec2 q = (uv - 0.5) * asp;
        float ang = (atan(q.y, q.x) + 3.14159) / 6.28318;
        r = mix(a, b, smoothstep(ang - 0.01, ang + 0.01, p));
    }
    if (uKind == 33) { // SPLIT
        float d = abs(uv.y - 0.5);
        r = d < p * 0.5 ? b : a;
        r = mix(b, a, smoothstep(p * 0.5 - 0.005, p * 0.5 + 0.005, d));
    }
    if (uKind == 36) { // CUSTOM_MASK (luma gradient image)
        float l = texture(uMaskTex, vec2(uv.x, 1.0 - uv.y)).r;
        r = mix(a, b, smoothstep(l - 0.08, l + 0.08, p * 1.16 - 0.08));
    }
    o = r;
}
"""

    /** Separable gaussian blur (premultiplied), radius in pixels, direction (1,0) or (0,1). */
    const val BLUR = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform vec2 uOut, uDir;
uniform float uRadius;
out vec4 o;
void main() {
    vec2 uv = gl_FragCoord.xy / uOut;
    if (uRadius < 0.5) { o = texture(uTex, uv); return; }
    float sigma = uRadius / 2.5;
    float step = max(1.0, uRadius / 12.0);
    vec4 s = vec4(0.0); float w = 0.0;
    for (int i = -12; i <= 12; i++) {
        float x = float(i) * step;
        float g = exp(-0.5 * x * x / (sigma * sigma));
        s += texture(uTex, uv + uDir * x / uOut) * g; w += g;
    }
    o = s / w;
}
"""

    /** Single-pass effects and multi-pass combiners (uBlur is a blurred copy where needed). */
    val FX = """#version 300 es
precision highp float;
$COMMON
uniform sampler2D uTex, uBlur;
uniform vec2 uOut;
uniform int uFx;
uniform float uP0, uP1, uP2, uP3, uP4, uTime;
out vec4 o;
vec4 S(vec2 uv) { return texture(uTex, clamp(uv, 0.0, 1.0)); }
void main() {
    vec2 uv = gl_FragCoord.xy / uOut;
    vec2 asp = vec2(uOut.x / uOut.y, 1.0);
    vec4 c = S(uv);
    vec3 rgb = c.a > 0.0 ? c.rgb / c.a : vec3(0.0);
    if (uFx == 1) { // directional blur: length, angle
        vec2 d = vec2(cos(radians(uP1)), sin(radians(uP1))) * uP0; vec4 s = vec4(0.0);
        for (int i = 0; i < 24; i++) s += S(uv + d * (float(i) / 23.0 - 0.5)); o = s / 24.0; return;
    }
    if (uFx == 2) { // radial blur: angle, cx, cy
        vec2 ce = vec2(uP1, 1.0 - uP2); vec4 s = vec4(0.0);
        for (int i = 0; i < 24; i++) { float a = radians(uP0) * (float(i) / 23.0 - 0.5); vec2 q = (uv - ce) * asp; q = mat2(cos(a), -sin(a), sin(a), cos(a)) * q; s += S(q / asp + ce); }
        o = s / 24.0; return;
    }
    if (uFx == 3) { // zoom blur
        vec2 ce = vec2(uP1, 1.0 - uP2); vec4 s = vec4(0.0);
        for (int i = 0; i < 24; i++) s += S(ce + (uv - ce) * (1.0 - uP0 * float(i) / 23.0)); o = s / 24.0; return;
    }
    if (uFx == 4) { // sharpen (unsharp mask): amount
        vec4 b = texture(uBlur, uv); o = vec4(clamp(c.rgb + (c.rgb - b.rgb) * uP0, 0.0, c.a), c.a); return;
    }
    if (uFx == 5) { // denoise (bilateral 5x5): strength
        vec4 s = vec4(0.0); float w = 0.0; float sr = 0.05 + uP0 * 0.25;
        for (int i = -2; i <= 2; i++) for (int j = -2; j <= 2; j++) {
            vec4 q = S(uv + vec2(i, j) / uOut); float d = distance(q.rgb, c.rgb);
            float g = exp(-float(i*i + j*j) / 8.0) * exp(-d * d / (2.0 * sr * sr)); s += q * g; w += g; }
        o = mix(c, s / w, clamp(uP0 * 1.5, 0.0, 1.0)); return;
    }
    if (uFx == 6) { // glow / bloom / halation / diffusion combine: intensity, tintR, tintG, tintB, mode
        vec4 b = texture(uBlur, uv);
        vec3 tint = vec3(uP1, uP2, uP3);
        if (uP4 > 2.5) { o = vec4(mix(c.rgb, max(c.rgb, b.rgb), uP0), c.a); return; } // diffusion (lighten mix)
        o = vec4(c.rgb + b.rgb * tint * uP0 * (1.0 - c.rgb * (uP4 > 1.5 ? 0.0 : 0.5)), max(c.a, b.a * uP0)); return;
    }
    if (uFx == 7) { // vignette: amount, size, softness
        float d = length((uv - 0.5) * asp * 1.4);
        float v = smoothstep(uP1, uP1 - max(uP2, 0.01), d);
        vec3 col = uP0 >= 0.0 ? rgb * mix(1.0, v, uP0) : mix(rgb, vec3(1.0), (1.0 - v) * -uP0);
        o = vec4(col * c.a, c.a); return;
    }
    if (uFx == 8) { // film grain: amount, size, seed
        float g = hash12(floor(gl_FragCoord.xy / max(uP1, 0.5)) + vec2(uP2 * 13.0, floor(uTime * 24.0) * 7.0)) - 0.5;
        float l = luma(rgb); float w = 1.0 - abs(l - 0.5) * 1.2;
        o = vec4(clamp(rgb + g * uP0 * 0.35 * w, 0.0, 1.0) * c.a, c.a); return;
    }
    if (uFx == 9) { // chromatic aberration: amount
        vec2 d = (uv - 0.5) * uP0 * 2.0;
        o = vec4(S(uv + d).r, c.g, S(uv - d).b, c.a); return;
    }
    if (uFx == 10) { // lens distortion: k, zoom
        vec2 q = (uv - 0.5) * asp; float r2 = dot(q, q); q *= (1.0 + uP0 * r2) / uP1; o = S(q / asp + 0.5); return;
    }
    if (uFx == 11) { // scanlines: amount, density
        float l = 0.5 + 0.5 * sin(uv.y * uP1 * 3.14159);
        o = vec4(c.rgb * (1.0 - uP0 * l), c.a); return;
    }
    if (uFx == 12) { // VHS: amount, seed
        float t = floor(uTime * 30.0);
        float jitter = (vnoise(vec2(uv.y * 80.0, t + uP1)) - 0.5) * 0.006 * uP0;
        float bleed = 0.004 * uP0;
        vec3 v = vec3(S(uv + vec2(jitter + bleed, 0.0)).r, S(uv + vec2(jitter, 0.0)).g, S(uv + vec2(jitter - bleed, 0.0)).b);
        float noise = hash12(gl_FragCoord.xy + t) * 0.08 * uP0;
        float tracking = smoothstep(0.98, 1.0, sin(uv.y * 3.0 + uTime * 2.0)) * 0.3 * uP0;
        v = mix(v, vec3(luma(v)), 0.25 * uP0) + noise + tracking;
        o = vec4(clamp(v, 0.0, 1.0) * c.a, c.a); return;
    }
    if (uFx == 13) { // RGB split: amount, angle
        vec2 d = vec2(cos(radians(uP1)), sin(radians(uP1))) * uP0;
        o = vec4(S(uv + d).r, c.g, S(uv - d).b, max(c.a, max(S(uv + d).a, S(uv - d).a))); return;
    }
    if (uFx == 14) { // glitch: amount, rate, seed
        float t = floor(uTime * uP1);
        float band = floor(uv.y * 30.0);
        float on = step(1.0 - uP0 * 0.5, hash12(vec2(band, t + uP2)));
        float off = (hash12(vec2(t, band + uP2)) - 0.5) * 0.2 * on * uP0;
        vec2 q = uv + vec2(off, 0.0);
        o = vec4(S(q + vec2(0.01 * uP0 * on, 0.0)).r, S(q).g, S(q - vec2(0.01 * uP0 * on, 0.0)).b, S(q).a); return;
    }
    if (uFx == 15) { // pixelate: size
        vec2 cell = vec2(max(uP0, 1.0)) / uOut; o = S((floor(uv / cell) + 0.5) * cell); return;
    }
    if (uFx == 16) { // hex mosaic: size
        vec2 p = uv * uOut / max(uP0, 2.0); vec2 r = vec2(1.0, 1.732); vec2 h = r * 0.5;
        vec2 a = mod(p, r) - h; vec2 b = mod(p - h, r) - h; vec2 g = dot(a, a) < dot(b, b) ? p - a : p - b;
        o = S(g * max(uP0, 2.0) / uOut); return;
    }
    if (uFx == 17 || uFx == 18) { // displacement / turbulent: amount, scale, speed, seed
        vec2 d = vec2(fbm(uv * uP1 + uTime * uP2 + uP3), fbm(uv * uP1 + 5.2 - uTime * uP2 + uP3)) - 0.5;
        if (uFx == 18) d = vec2(sin(d.y * 12.0 + uTime * uP2), cos(d.x * 12.0 - uTime * uP2)) * 0.5;
        o = S(uv + d * uP0 * 2.0); return;
    }
    if (uFx == 19) { // drop shadow combine: distance, angle, opacity (uBlur = blurred alpha)
        vec2 off = vec2(cos(radians(uP1)), -sin(radians(uP1))) * uP0 * asp.yx;
        float sa = texture(uBlur, uv - off).a * uP2;
        o = c + vec4(0.0, 0.0, 0.0, sa) * (1.0 - c.a); return;
    }
    if (uFx == 20) { // reflection: opacity, falloff (mirror below content bottom)
        vec2 q = vec2(uv.x, -uv.y + 2.0 * 0.0); float f = 1.0 - smoothstep(0.0, max(uP1, 0.01), uv.y);
        vec4 r = S(vec2(uv.x, 1.0 - uv.y * 2.0 + 0.0)) * uP0 * f;
        o = c + r * (1.0 - c.a); return;
    }
    if (uFx == 21) { // outline: width, r, g, b
        float a = 0.0; for (int i = 0; i < 16; i++) { float ang = float(i) * 0.3927; a = max(a, S(uv + vec2(cos(ang), sin(ang)) * uP0 / uOut).a); }
        vec4 ol = vec4(vec3(uP1, uP2, uP3) * a, a);
        o = c + ol * (1.0 - c.a); return;
    }
    if (uFx == 22) { // edge detect: amount
        float gx = 0.0, gy = 0.0;
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) { float l = luma(S(uv + vec2(i, j) / uOut).rgb);
            gx += l * float(i) * (j == 0 ? 2.0 : 1.0); gy += l * float(j) * (i == 0 ? 2.0 : 1.0); }
        float e = length(vec2(gx, gy));
        o = vec4(mix(c.rgb, vec3(e) * c.a, uP0), c.a); return;
    }
    if (uFx == 23) { // duotone: shadowHue, highlightHue, amount
        float l = luma(rgb);
        vec3 d = mix(hsl2rgb(vec3(uP0 / 360.0, 0.7, 0.25)), hsl2rgb(vec3(uP1 / 360.0, 0.8, 0.75)), l);
        o = vec4(mix(rgb, d, uP2) * c.a, c.a); return;
    }
    if (uFx == 24) { // gradient map: hueA, hueB, amount
        float l = luma(rgb);
        vec3 d = mix(hsl2rgb(vec3(uP0 / 360.0, 0.8, l * 0.5)), hsl2rgb(vec3(uP1 / 360.0, 0.8, 0.5 + l * 0.5)), l);
        o = vec4(mix(rgb, d, uP2) * c.a, c.a); return;
    }
    if (uFx == 25) { // posterize: levels
        o = vec4(floor(rgb * uP0) / max(uP0 - 1.0, 1.0) * c.a, c.a); return;
    }
    if (uFx == 26) { // invert: amount
        o = vec4(mix(rgb, 1.0 - rgb, uP0) * c.a, c.a); return;
    }
    if (uFx == 27) { // letterbox: ratio, opacity
        float target = 1.0 / uP0 * (uOut.x / uOut.y); float bar = max(0.0, (1.0 - target) * 0.5);
        float inBar = step(uv.y, bar) + step(1.0 - bar, uv.y);
        o = mix(c, vec4(0.0, 0.0, 0.0, 1.0), min(inBar, 1.0) * uP1); return;
    }
    if (uFx == 28) { // film gate: roundness, weave, seed
        vec2 w = vec2(vnoise(vec2(uTime * 6.0, uP2)) - 0.5, vnoise(vec2(uP2, uTime * 6.0)) - 0.5) * 0.006 * uP1;
        vec2 q = uv + w; vec2 e = abs(q - 0.5) * 2.0;
        float r = max(uP0 * 0.2, 0.001);
        float d = length(max(e - (1.0 - r), 0.0)) / r;
        float m = 1.0 - smoothstep(0.9, 1.0, d);
        o = S(q) * m + vec4(0.0, 0.0, 0.0, 1.0) * (1.0 - m); return;
    }
    if (uFx == 29) { // particles: density, size, speed, seed
        vec3 add = vec3(0.0);
        for (int L = 0; L < 3; L++) {
            float sc = 20.0 + float(L) * 15.0;
            vec2 p = uv * vec2(sc * asp.x, sc) + vec2(0.0, uTime * uP2 * (1.0 + float(L)));
            vec2 id = floor(p); vec2 f = fract(p);
            float h = hash12(id + uP3 + float(L) * 31.0);
            if (h < uP0 * 0.35) { vec2 pos = vec2(hash12(id + 1.7), hash12(id + 9.1)); float d = length(f - pos);
                add += vec3(1.0) * smoothstep(0.04 * uP1, 0.0, d) * (0.5 + 0.5 * sin(uTime * 3.0 + h * 20.0)); }
        }
        o = vec4(c.rgb + add * (1.0 - c.rgb), max(c.a, min(1.0, luma(add)))); return;
    }
    if (uFx == 30) { // mirror: axis
        vec2 q = uP0 < 0.5 ? vec2(uv.x < 0.5 ? uv.x : 1.0 - uv.x, uv.y) : vec2(uv.x, uv.y > 0.5 ? uv.y : 1.0 - uv.y);
        o = S(q); return;
    }
    if (uFx == 31) { // light leak: amount, hue, speed, seed
        float n = fbm(uv * 1.5 + vec2(uTime * uP2, uP3));
        vec3 col = hsl2rgb(vec3(uP1 / 360.0, 0.9, 0.6));
        float m = smoothstep(0.45, 0.9, n) * uP0;
        o = vec4(c.rgb + col * m * (1.0 - c.rgb * 0.5), max(c.a, m)); return;
    }
    if (uFx == 32) { // anamorphic flare combine: intensity (uBlur = horizontal streak)
        vec4 b = texture(uBlur, uv); o = vec4(c.rgb + b.rgb * vec3(0.45, 0.65, 1.0) * uP0, max(c.a, b.a * uP0)); return;
    }
    if (uFx == 33) { // threshold (bright pass): threshold
        float l = luma(rgb); float k = smoothstep(uP0, min(uP0 + 0.15, 1.0), l); o = vec4(c.rgb * k, c.a * k); return;
    }
    if (uFx == 34) { // luma key: threshold, softness, invert
        float a = smoothstep(uP0, uP0 + max(uP1, 0.001), luma(rgb)); if (uP2 > 0.5) a = 1.0 - a; o = c * a; return;
    }
    if (uFx == 35) { // flicker gain: gain
        o = vec4(clamp(c.rgb * uP0, 0.0, c.a), c.a); return;
    }
    if (uFx == 36) { // lens blur combine with highlight boost: highlights (uBlur = blurred)
        vec4 b = texture(uBlur, uv); o = b + vec4(b.rgb * smoothstep(0.7, 1.0, luma(b.rgb)) * uP0, 0.0); return;
    }
    o = c;
}
"""

    /** Optical-flow frame interpolation: coarse block-matching flow, then bidirectional warp and blend. */
    val FLOW = """#version 300 es
precision highp float;
$COMMON
uniform sampler2D uA, uB;
uniform vec2 uOut;
uniform float uT;
out vec4 o;
float L(sampler2D s, vec2 uv) { return luma(texture(s, uv).rgb); }
void main() {
    vec2 uv = gl_FragCoord.xy / uOut;
    vec2 px = 8.0 / uOut;            // search on an 8-px grid
    float best = 1e9; vec2 bv = vec2(0.0);
    for (int i = -4; i <= 4; i++) for (int j = -4; j <= 4; j++) {
        vec2 d = vec2(i, j) * px;
        float sad = 0.0;
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
            vec2 q = uv + vec2(a, b) * px * 0.5;
            sad += abs(L(uA, q) - L(uB, q + d));
        }
        sad += length(vec2(i, j)) * 0.002;
        if (sad < best) { best = sad; bv = d; }
    }
    vec4 fa = texture(uA, uv - bv * uT);
    vec4 fb = texture(uB, uv + bv * (1.0 - uT));
    o = mix(fa, fb, uT);
}
"""

    /** Mixes two frames linearly (frame blending). */
    const val MIX = """#version 300 es
precision highp float;
uniform sampler2D uA, uB;
uniform vec2 uOut;
uniform float uT;
out vec4 o;
void main() { vec2 uv = gl_FragCoord.xy / uOut; o = mix(texture(uA, uv), texture(uB, uv), uT); }
"""

    /** Final output pass: premultiplied → straight for encoders (alpha dropped) or kept for PNG. */
    const val PRESENT = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform vec2 uOut, uSrc, uOffset;
uniform int uFlip;
uniform vec4 uBg;
out vec4 o;
void main() {
    vec2 uv = (gl_FragCoord.xy - uOffset) / uOut;
    if (uFlip == 1) uv.y = 1.0 - uv.y;
    vec4 c = texture(uTex, uv);
    o = vec4(c.rgb + uBg.rgb * (1.0 - c.a), max(c.a, uBg.a));
}
"""
}
