float hh(float x) { return fract(sin(x * 91.17) * 43758.5453); }
void main() {
  vec2 p = uvr(); float r = length(p); float a = atan(p.y, p.x);
  float k = (a + 3.14159) / 6.28318 * (60.0 + 200.0 * DEN);
  float id = floor(k); float f = fract(k);
  float s0 = hh(id); float s1 = hh(id + 1.0);
  float streak = mix(s0, s1, f * f * (3.0 - 2.0 * f));
  float wave = 0.5 + 0.5 * sin(r * 34.0 - T * 1.2 + streak * 6.0);
  float pupil = smoothstep(0.075, 0.09, r);
  float body = smoothstep(0.44, 0.40, r) * pupil;
  vec3 c = mix(hsv(HUE + 0.88, 0.95, 0.75), hsv(HUE + 0.62, 0.6, 1.0), smoothstep(0.08, 0.42, r));
  vec3 col = c * body * (0.25 + 1.1 * streak * (0.55 + 0.45 * wave));
  col += vec3(1.0) * smoothstep(0.012, 0.0, abs(r - 0.44)) * 0.6;
  float tooth = 0.45 + 0.012 * sin(a * 120.0);
  col += hsv(HUE + 0.75, 0.3, 1.0) * smoothstep(0.006, 0.0, abs(r - tooth));
  col += hsv(HUE + 0.7, 0.35, 1.0) * streak * smoothstep(0.75, 0.44, r) * smoothstep(0.43, 0.46, r) * 0.28;
  gl_FragColor = vec4(post(col * GLOW), 1.0);
}
