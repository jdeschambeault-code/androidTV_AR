#!/usr/bin/env python3
"""Checks the vertex recipes used in Solids.kt: each solid must have its known number of vertices and edges,
every vertex the same distance from the centre, and every edge the same length."""
import itertools, math

PHI = (1 + 5 ** 0.5) / 2
R2 = 2 ** 0.5
T = 1.839286755214161       # tribonacci constant, for the snub cube


def uniq(v):
    out = []
    for p in v:
        if not any(all(abs(a - b) < 1e-6 for a, b in zip(p, q)) for q in out): out.append(tuple(p))
    return out


def signs(p):
    return itertools.product(*[(x, -x) if x else (0.0,) for x in p])


def allp(*groups):
    return uniq([q for p in groups for s in signs(p) for q in itertools.permutations(s)])


def even(*groups):
    return uniq([q for p in groups for s in signs(p) for q in ((s[0], s[1], s[2]), (s[1], s[2], s[0]), (s[2], s[0], s[1]))])


def snub_cube():
    a, b, c = 1.0, 1 / T, T
    out = []
    for sx, sy, sz in itertools.product((1, -1), repeat=3):
        minus = (sx < 0) + (sy < 0) + (sz < 0)
        s = (sx * a, sy * b, sz * c)
        if minus % 2 == 0:   # even permutations with an even number of minus signs
            out += [(s[0], s[1], s[2]), (s[1], s[2], s[0]), (s[2], s[0], s[1])]
        else:                # odd permutations with an odd number of minus signs
            out += [(s[1], s[0], s[2]), (s[0], s[2], s[1]), (s[2], s[1], s[0])]
    return uniq(out)


def snub_dodeca():
    x = (PHI / 2 + 0.5 * (PHI - 5 / 27) ** 0.5) ** (1 / 3) + (PHI / 2 - 0.5 * (PHI - 5 / 27) ** 0.5) ** (1 / 3)
    a = x - 1 / x
    b = x * PHI + PHI * PHI + PHI / x
    base = [(2 * a, 2.0, 2 * b),
            (a + b / PHI + PHI, -a * PHI + b + 1 / PHI, a / PHI + b * PHI - 1),
            (-a / PHI + b * PHI + 1, -a + b / PHI - PHI, a * PHI + b - 1 / PHI),
            (-a / PHI + b * PHI - 1, a - b / PHI - PHI, a * PHI + b + 1 / PHI),
            (a + b / PHI - PHI, a * PHI - b + 1 / PHI, a / PHI + b * PHI + 1)]
    out = []
    for p in base:
        for sx, sy, sz in itertools.product((1, -1), repeat=3):
            if ((sx > 0) + (sy > 0) + (sz > 0)) % 2 == 0:   # an even number of plus signs
                s = (sx * p[0], sy * p[1], sz * p[2])
                out += [(s[0], s[1], s[2]), (s[1], s[2], s[0]), (s[2], s[0], s[1])]
    return uniq(out)


SOLIDS = {
    "Truncated tetrahedron": (uniq([q for s in signs((1.0, 1.0, 3.0)) if sum(x < 0 for x in s) % 2 == 0 for q in itertools.permutations(s)]), 12, 18),
    "Cuboctahedron": (allp((1.0, 1.0, 0.0)), 12, 24),
    "Truncated cube": (allp((R2 - 1, 1.0, 1.0)), 24, 36),
    "Truncated octahedron": (allp((0.0, 1.0, 2.0)), 24, 36),
    "Rhombicuboctahedron": (allp((1.0, 1.0, 1 + R2)), 24, 48),
    "Truncated cuboctahedron": (allp((1.0, 1 + R2, 1 + 2 * R2)), 48, 72),
    "Snub cube": (snub_cube(), 24, 60),
    "Icosidodecahedron": (uniq(allp((0.0, 0.0, PHI)) + even((0.5, PHI / 2, PHI * PHI / 2))), 30, 60),
    "Truncated dodecahedron": (even((0.0, 1 / PHI, 2 + PHI), (1 / PHI, PHI, 2 * PHI), (PHI, 2.0, PHI + 1)), 60, 90),
    "Truncated icosahedron": (even((0.0, 1.0, 3 * PHI), (1.0, 2 + PHI, 2 * PHI), (PHI, 2.0, 2 * PHI + 1)), 60, 90),
    "Rhombicosidodecahedron": (even((1.0, 1.0, PHI ** 3), (PHI ** 2, PHI, 2 * PHI), (2 + PHI, 0.0, PHI ** 2)), 60, 120),
    "Truncated icosidodecahedron": (even((1 / PHI, 1 / PHI, 3 + PHI), (2 / PHI, PHI, 1 + 2 * PHI), (1 / PHI, PHI ** 2, 3 * PHI - 1), (2 * PHI - 1, 2.0, 2 + PHI), (PHI, 3.0, 2 * PHI)), 120, 180),
    "Snub dodecahedron": (snub_dodeca(), 60, 150),
}


def check(name, v, want_v, want_e):
    d = lambda a, b: math.dist(a, b)
    dist = sorted(d(a, b) for a, b in itertools.combinations(v, 2))
    low = dist[0]
    e = sum(1 for x in dist if x < low * 1.01)
    radii = [math.hypot(*p) for p in v]
    same = max(radii) - min(radii) < 1e-5
    ok = len(v) == want_v and e == want_e and same
    print("%-4s %-30s %3d vertices (want %3d), %3d edges (want %3d), on one sphere: %s" % ("ok" if ok else "FAIL", name, len(v), want_v, e, want_e, same))
    return ok


if __name__ == "__main__":
    results = [check(n, *t) for n, t in SOLIDS.items()]
    print("%d of %d correct" % (sum(results), len(results)))
