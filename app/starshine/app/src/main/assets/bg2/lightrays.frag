// Light rays: sunlight falling through a canopy from above the top edge. A few soft beams whose strength flickers slowly,
// as leaves move across the light. Made to lie over a forest or bamboo photo (blend Screen or Add).
// Glow = brightness, Detail = how many beams, Colour = tint of the light, Speed = how fast the leaves move.
void main() {
  vec2 p = uvr();
  vec2 s = vec2(-0.55, 0.95);                 // the light: above the top edge, a little to the left
  vec2 d = p - s; float a = atan(d.x, -d.y); float r = length(d);
  float t = T * 0.15;
  float n = 3.0 + floor(DEN * 8.0);           // 3..11 beams
  float b = 0.0;
  for (int i = 0; i < 11; i++) {
    float fi = float(i); if (fi >= n) break;
    float ang = -0.2 + fi * (0.55 / n) + 0.02 * sin(t + fi * 2.3);
    float w = 0.012 + 0.012 * fract(fi * 0.37);
    float flick = 0.55 + 0.45 * sin(t * (1.3 + fi * 0.21) + fi * 4.1) * sin(t * 0.7 + fi * 1.7);
    b += exp(-pow((a - ang) / w, 2.0)) * flick;
  }
  b *= smoothstep(1.9, 0.25, r) * smoothstep(0.0, 0.3, r);
  vec3 col = mix(vec3(1.0, 0.95, 0.8), hsv(HUE + 0.12, 0.4, 1.0), 0.3) * b * 0.35 * GLOW;
  gl_FragColor = vec4(post(col), 1.0);
}
