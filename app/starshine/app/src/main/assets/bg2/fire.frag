float h(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float n(vec2 p) { vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
  return mix(mix(h(i), h(i + vec2(1.0, 0.0)), f.x), mix(h(i + vec2(0.0, 1.0)), h(i + vec2(1.0, 1.0)), f.x), f.y); }
float fbm(vec2 p) { float v = 0.0; float a = 0.5; for (int k = 0; k < 4; k++) { v += a * n(p); p = p * 2.03 + 1.7; a *= 0.5; } return v; }
void main() {
  vec2 p = uvr() * vec2(2.0 + 3.0 * DEN, 1.4 + 2.0 * DEN); float t = T * 0.35;
  float v = fbm(p + vec2(0.0, -t) + 0.9 * fbm(p * 1.3 + vec2(1.7, -t * 1.4)));
  v = pow(clamp(v * 1.35, 0.0, 1.0), 2.2);
  vec3 col = mix(hsv(HUE + 0.0, 0.95, 0.35), hsv(HUE + 0.08, 0.9, 1.0), smoothstep(0.05, 0.45, v));
  col = mix(col, vec3(1.0, 0.95, 0.75), smoothstep(0.45, 0.95, v));
  gl_FragColor = vec4(post(col * v * 1.35 * GLOW), 1.0);
}
