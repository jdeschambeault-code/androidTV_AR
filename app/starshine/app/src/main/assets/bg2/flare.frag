void main() {
  vec2 p = uvr(); float r = length(p);
  float pulse = 0.92 + 0.08 * sin(T * 0.8);
  float core = 0.018 / (r + 0.012);
  float wide = exp(-r * r * 18.0) * 0.55;
  float streak = exp(-abs(p.y) * 70.0) * exp(-abs(p.x) * (1.4 + 3.0 * DEN)) * 1.1;
  float rays = pow(abs(sin(atan(p.y, p.x) * 6.0 + T * 0.05)), 40.0) * exp(-r * 6.0) * 0.5;
  vec3 warm = hsv(HUE + 0.09, 0.75, 1.0);
  vec3 col = (vec3(1.0) * core + warm * (wide + streak + rays)) * pulse;
  gl_FragColor = vec4(post(col * GLOW), 1.0);
}
