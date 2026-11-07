void main() {
  vec2 p = uvr(); float r = length(p); float a = atan(p.y, p.x);
  float b = 0.5 + 0.5 * sin(a * (3.0 + floor(DEN * 9.0)) + log(r + 0.02) * 3.0 - T * 0.35);
  vec3 col = mix(hsv(HUE + 0.72, 0.75, 0.55), hsv(HUE + 0.48, 0.65, 0.95), b) * exp(-r * 2.6);
  gl_FragColor = vec4(post(col * GLOW), 1.0);
}
