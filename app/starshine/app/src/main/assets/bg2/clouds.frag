float h(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float n(vec2 p) { vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
  return mix(mix(h(i), h(i + vec2(1.0, 0.0)), f.x), mix(h(i + vec2(0.0, 1.0)), h(i + vec2(1.0, 1.0)), f.x), f.y); }
float fbm(vec2 p) { float v = 0.0; float a = 0.5; for (int k = 0; k < 4; k++) { v += a * n(p); p = p * 2.03 + 3.1; a *= 0.5; } return v; }
void main() {
  vec2 p = uvr() * (1.5 + 2.5 * DEN); float t = T * 0.03;
  float v = fbm(p + vec2(t, t * 0.4) + fbm(p - t) * 0.8);
  float c = smoothstep(0.26, 0.60, v);
  vec3 col = mix(hsv(HUE + 0.60, 0.62, 0.95), vec3(1.0), c) * (0.7 + 0.45 * c) * GLOW;
  gl_FragColor = vec4(post(col), 1.0);
}
