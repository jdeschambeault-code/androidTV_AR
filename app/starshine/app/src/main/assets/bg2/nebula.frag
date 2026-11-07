float h(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float n(vec2 p) { vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
  return mix(mix(h(i), h(i + vec2(1.0, 0.0)), f.x), mix(h(i + vec2(0.0, 1.0)), h(i + vec2(1.0, 1.0)), f.x), f.y); }
float fbm(vec2 p) { float v = 0.0; float a = 0.5; for (int k = 0; k < 4; k++) { v += a * n(p); p = p * 2.03 + 4.3; a *= 0.5; } return v; }
void main() {
  vec2 p = uvr() * (1.2 + 2.0 * DEN); float t = T * 0.02;
  vec2 q = vec2(fbm(p + t), fbm(p + vec2(5.2, 1.3) - t));
  float v = fbm(p + 1.1 * q);
  float d = smoothstep(0.28, 0.9, v);
  vec3 col = mix(hsv(HUE + 0.72, 0.8, 0.5), hsv(HUE + 0.92 + q.x * 0.2, 0.75, 1.0), d) * d * d * 1.5;
  gl_FragColor = vec4(post(col * GLOW), 1.0);
}
