void main() {
  vec2 p = uvr() * (10.0 + 40.0 * DEN);
  vec2 s = vec2(1.0, 1.7320508); vec2 hs = s * 0.5;
  vec2 a = mod(p, s) - hs; vec2 b = mod(p - hs, s) - hs;
  vec2 g = dot(a, a) < dot(b, b) ? a : b;
  vec2 c = abs(g); float d = max(dot(c, s * 0.5), c.x);
  float cell = smoothstep(0.5, 0.40, d);
  vec2 id = p - g;
  vec3 tint = hsv(HUE + length(id) * 0.018 - T * 0.02, 0.55, 1.0);
  gl_FragColor = vec4(post(tint * cell * GLOW), 1.0);
}
