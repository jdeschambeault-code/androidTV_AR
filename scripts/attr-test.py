#!/usr/bin/env python3
"""Checks every number you can edit in the Bg and Mood builders, by pressing ← → on it in simulation.

It reads the real ranges and steps from the source, and steps a value the way the app does it, in 32-bit floats.
Each attribute is walked from its minimum to its maximum and back, plus a fine walk around its default. It reports:

  STUCK      a press doesn't change the value
  INVISIBLE  a press changes the value but what's shown stays the same
  DRIFT      pressing + then - the same number of times doesn't return to the start, as shown
  OFFGRID    the value shown stops being a clean multiple of the step
  LIMIT      the walk can't reach the maximum, or leaves the range
"""
import os, re, struct, sys

SRC = os.path.join(os.path.dirname(__file__), "..", "app", "starshine", "app", "src", "main", "java", "com", "starshine", "launcher")
f32 = lambda x: struct.unpack("f", struct.pack("f", x))[0]
num = lambda s: f32(float(s.rstrip("f")))


def snapped(v, step, direction):
    """The app's stepping once Stepper.kt is in use: worked out in doubles, kept to three decimals."""
    return f32(round((v + direction * step) * 1000.0) / 1000.0)


def plain(v, step, direction):
    """The app's stepping before Stepper.kt: adds a float to a float."""
    return f32(f32(v) + direction * f32(step))


def decimals(step):
    s = ("%.6f" % step).rstrip("0")
    return len(s.split(".")[1])


class Attr:
    def __init__(self, group, key, label, step, lo, hi, show, default):
        self.group, self.key, self.label, self.step, self.lo, self.hi, self.show, self.default = group, key, label, step, lo, hi, show, default


def bg_attrs():
    text = open(os.path.join(SRC, "Bg2Model.kt"), encoding="utf-8").read()
    out = []
    for m in re.finditer(r'P\("(\w+)", "([^"]+)", (-?[\d.]+)f, (-?[\d.]+)f, (-?[\d.]+)f, (-?[\d.]+)f(?:, ([^\n]+))?\)\n', text):
        key, label, default, step, lo, hi, fmt = m.groups()
        fmt = fmt or ""
        if "num(" in fmt: show = lambda v: "%.2f" % v
        elif "deg" in fmt or "Math.round" in fmt: show = lambda v: str(round(v))
        else: show = lambda v: "%d%%" % round(v * 100)
        out.append(Attr("Scene layer", key, label, num(step), num(lo), num(hi), show, num(default)))
    return out


def mood_attrs():
    text = open(os.path.join(SRC, "MoodUi.kt"), encoding="utf-8").read()
    defaults = open(os.path.join(SRC, "Meditation.kt"), encoding="utf-8").read()
    table = dict((k, float(v)) for k, v in re.findall(r'"(\w+)" to (-?[\d.]+)f', defaults[defaults.index("val DEFAULTS = mapOf"):defaults.index("fun fromJson(o: JSONObject): AudioLayer")]))
    out = []
    for m in re.finditer(r'num\("(\w+)", "([^"]+)", ([\d.]+)f, (\d), (-?[\d.]+)f, (-?[\d.]+)f', text):
        key, label, step, d, lo, hi = m.groups()
        fmt = "%." + d + "f"
        out.append(Attr("Mood layer", key, label, num(step), num(lo), num(hi), (lambda f: lambda v: f % v)(fmt), f32(table.get(key, float(lo)))))
    return out


def check(a, stepper):
    problems = []
    # a whole walk from the minimum, capped so very fine steps stay quick
    v = a.lo; presses = 0; stuck = invisible = 0; first_stuck = None; first_inv = None
    limit = min(40000, int((a.hi - a.lo) / a.step) + 3)
    while v < a.hi and presses < limit:
        n = min(stepper(v, a.step, 1), a.hi)
        if n == v and stuck == 0: first_stuck = v
        if n == v: stuck += 1
        elif a.show(n) == a.show(v):
            invisible += 1
            if first_inv is None: first_inv = a.show(v)
        v = n; presses += 1
    if v > a.hi or v < a.lo: problems.append(("LIMIT", "left the range: %r" % v))
    if presses >= limit and v < a.hi and (a.hi - a.lo) / a.step < 40000: problems.append(("LIMIT", "stopped at %s, short of %s" % (a.show(v), a.show(a.hi))))
    if stuck: problems.append(("STUCK", "%d presses do nothing, from about %s" % (stuck, a.show(first_stuck))))
    if invisible: problems.append(("INVISIBLE", "%d of %d presses change nothing on screen, first at %s" % (invisible, presses, first_inv)))
    # the realistic use: fifty presses up and fifty down, around a few values within the range
    starts = [a.default] + [s for s in (136.1, 432.0, 1000.0, 8000.0, 16000.0) if a.key in ("carrier", "carrierTo") and a.lo <= s <= a.hi]
    for s in starts:
        s = f32(s); v = s; pressed = 0
        for _ in range(50):
            if v + a.step > a.hi: break
            v = stepper(v, a.step, 1); pressed += 1
        peak = v
        for _ in range(pressed): v = stepper(v, a.step, -1)
        if a.show(v) != a.show(s): problems.append(("DRIFT", "from %s, %d up and %d down ends on %s" % (a.show(s), pressed, pressed, a.show(v))))
        d = decimals(a.step)
        expect = round(s + pressed * a.step, d)
        shown = a.show(peak)
        if "%" not in shown and shown.replace("-", "").replace(".", "").isdigit() and abs(float(shown) - expect) >= (10 ** -d) / 2 and d <= 3:
            problems.append(("OFFGRID", "from %s, %d presses of %g show %s, should be %s" % (a.show(s), pressed, a.step, shown, a.show(expect))))
    return problems


def main():
    attrs = bg_attrs() + mood_attrs()
    if not attrs: sys.exit("No attributes found in the source: the test no longer matches it")
    for name, stepper in (("as the app stepped values before Stepper.kt", plain), ("with Stepper.kt", snapped)):
        total = 0; lines = []
        for a in attrs:
            seen = set()
            for kind, text in check(a, stepper):
                if (kind, text) in seen: continue
                seen.add((kind, text)); total += 1
                lines.append("  %-9s %-11s %-24s %s" % (kind, a.group, a.label, text))
        print("%d attributes, %s: %d problems" % (len(attrs), name, total))
        print("\n".join(lines))
        print()


if __name__ == "__main__":
    main()
