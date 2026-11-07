float h(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float n(vec2 p) { vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
  return mix(mix(h(i), h(i + vec2(1.0, 0.0)), f.x), mix(h(i + vec2(0.0, 1.0)), h(i + vec2(1.0, 1.0)), f.x), f.y); }
float fbm(vec2 p) { float v = 0.0; float a = 0.5; for (int k = 0; k < 4; k++) { v += a * n(p); p = p * 2.03 + 2.9; a *= 0.5; } return v; }
void main() {
  vec2 p = uvr() / 0.32; float r2 = dot(p, p);
  vec3 col = vec3(0.0);
  if (r2 < 1.0) {
    float z = sqrt(1.0 - r2);
    vec2 m = vec2(atan(p.x, z) * 1.6 + T * 0.03, asin(clamp(p.y, -1.0, 1.0)) * 2.0);
    float land = smoothstep(0.48, 0.54, fbm(m * (1.5 + 2.0 * DEN)));
    float cloud = smoothstep(0.55, 0.8, fbm(m * 2.7 + T * 0.02));
    vec3 base = mix(hsv(HUE + 0.59, 0.85, 0.5), hsv(HUE + 0.28, 0.5, 0.42), land);
    base = mix(base, vec3(0.95), cloud * 0.8);
    float light = clamp(dot(normalize(vec3(p, z)), normalize(vec3(-0.45, 0.45, 0.75))), 0.0, 1.0);
    col = base * (0.12 + 0.95 * light) + hsv(HUE + 0.57, 0.6, 1.0) * pow(1.0 - z, 3.0) * 0.8;
  }
  col += hsv(HUE + 0.57, 0.6, 1.0) * exp(-abs(sqrt(r2) - 1.0) * 16.0) * 0.28;
  gl_FragColor = vec4(post(col * GLOW), 1.0);
}
