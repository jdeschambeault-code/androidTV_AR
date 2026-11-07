void main() {
  vec2 p = uvr() * 1.25; float t = T;
  p.x += sin(p.y * 5.0 + t * 2.1) * 0.012 * (p.y + 0.45) + sin(t * 0.7) * 0.006;
  float y = (p.y + 0.3) / 0.75;
  float w = 0.085 * pow(clamp(y, 0.0, 1.0), 0.55) * pow(clamp(1.0 - y, 0.0, 1.0), 0.9) * 2.1;
  float d = abs(p.x) / max(w, 0.0005);
  float body = smoothstep(1.1, 0.2, d) * step(0.0, y) * step(y, 1.0);
  vec3 col = mix(hsv(HUE + 0.07, 0.95, 1.0), vec3(1.0, 0.96, 0.82), smoothstep(0.85, 0.15, d) * smoothstep(0.02, 0.28, y));
  col = mix(hsv(HUE + 0.62, 0.8, 0.8), col, smoothstep(0.0, 0.14, y));
  float halo = exp(-dot(p - vec2(0.0, 0.05), p - vec2(0.0, 0.05)) * 9.0) * 0.2;
  gl_FragColor = vec4(post((col * body + hsv(HUE + 0.08, 0.8, 1.0) * halo) * GLOW), 1.0);
}
