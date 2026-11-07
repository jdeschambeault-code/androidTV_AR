void main() {
  vec2 p = uvr() * (4.0 + 8.0 * DEN) + 40.0; float t = T * 0.25; vec2 i = p; float c = 1.0;
  for (int k = 0; k < 4; k++) {
    float tt = t * (1.0 - 0.3 * float(k));
    i = p + vec2(cos(tt - i.x) + sin(tt + i.y), sin(tt - i.y) + cos(tt + i.x));
    c += 1.0 / length(vec2(p.x / (sin(i.x + tt) / 0.012), p.y / (cos(i.y + tt) / 0.012)));
  }
  c = 1.2 - sqrt(c / 4.0 * 0.5 + 0.2);
  float g = pow(clamp(c, 0.0, 1.0), 3.0) * 2.4;
  vec3 col = hsv(HUE + 0.58, 0.85, 0.22) + hsv(HUE + 0.53, 0.55, 1.0) * g;
  gl_FragColor = vec4(post(col * GLOW), 1.0);
}
