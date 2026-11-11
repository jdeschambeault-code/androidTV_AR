package com.starshine.launcher

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One press on a number: worked out in doubles and kept to three decimals, so repeated presses can't drift. */
object Stepper {
    fun next(v: Float, step: Float, direction: Int, min: Float, max: Float): Float =
        (Math.round((v.toDouble() + direction * step.toDouble()) * 1000.0) / 1000.0).toFloat().coerceIn(min, max)
}

/**
 * More sacred geometry and solids, as line segments (x0 y0 z0 x1 y1 z1 ...). The vertex recipes are checked by scripts/solids-test.py.
 * Higher dimensions are projected down to 3D, then turned like any other solid.
 */
object Solids {
    const val HYPERCUBE = "Hypercube"
    const val SIMPLEX = "Simplex"
    const val CROSS = "Cross-polytope"

    val LINES = listOf("Vesica piscis", "Sri Yantra", "Tree of Life", "Hexagram", "Pentagram", "Enneagram", "Phyllotaxis", "Fibonacci squares")
    private val PRISMS = mapOf("Triangular prism" to 3, "Pentagonal prism" to 5, "Hexagonal prism" to 6, "Octagonal prism" to 8, "Decagonal prism" to 10)
    private val ANTIPRISMS = mapOf("Square antiprism" to 4, "Pentagonal antiprism" to 5, "Hexagonal antiprism" to 6, "Octagonal antiprism" to 8, "Decagonal antiprism" to 10)
    val SOLIDS = listOf(
        // the 13 Archimedean solids
        "Truncated tetrahedron", "Cuboctahedron", "Truncated cube", "Truncated octahedron", "Rhombicuboctahedron", "Truncated cuboctahedron", "Snub cube",
        "Icosidodecahedron", "Truncated dodecahedron", "Truncated icosahedron", "Rhombicosidodecahedron", "Truncated icosidodecahedron", "Snub dodecahedron",
        // the 4 Kepler-Poinsot star solids
        "Small stellated dodecahedron", "Great stellated dodecahedron", "Great dodecahedron", "Great icosahedron"
    ) + PRISMS.keys + ANTIPRISMS.keys + listOf(
        // some of the 92 Johnson solids
        "Square pyramid", "Pentagonal pyramid", "Triangular bipyramid", "Pentagonal bipyramid", "Square cupola", "Elongated square bipyramid",
        // curved
        "Sphere", "Cylinder", "Cone", "Möbius strip", "Klein bottle", "Trefoil knot",
        // higher dimensions
        HYPERCUBE, SIMPLEX, CROSS
    )

    fun isHyper(name: String) = name == HYPERCUBE || name == SIMPLEX || name == CROSS
    /** A hypercube doubles its corners with each dimension: 26D would have 67 million. The other two grow slowly. */
    fun maxDim(name: String) = if (name == HYPERCUBE) 10 else 26
    fun dimOf(name: String, stored: Float): Int = if (stored >= 4f) stored.toInt().coerceAtMost(maxDim(name)) else if (name == HYPERCUBE) 4 else 26

    private val PHI = (1 + sqrt(5.0)) / 2
    private val R2 = sqrt(2.0)
    private const val TAU = 2 * PI
    private const val TRIBONACCI = 1.839286755214161
    private class V(val x: Double, val y: Double, val z: Double)

    private fun signs(p: DoubleArray): List<DoubleArray> {
        var out = listOf(doubleArrayOf())
        for (x in p) out = out.flatMap { a -> (if (x == 0.0) listOf(0.0) else listOf(x, -x)).map { a + it } }
        return out
    }
    private fun cyclic(a: DoubleArray) = listOf(V(a[0], a[1], a[2]), V(a[1], a[2], a[0]), V(a[2], a[0], a[1]))
    private fun swapped(a: DoubleArray) = listOf(V(a[1], a[0], a[2]), V(a[0], a[2], a[1]), V(a[2], a[1], a[0]))
    private fun allPerms(vararg g: DoubleArray) = g.flatMap { p -> signs(p).flatMap { cyclic(it) + swapped(it) } }.distinctV()
    private fun evenPerms(vararg g: DoubleArray) = g.flatMap { p -> signs(p).flatMap { cyclic(it) } }.distinctV()
    private fun List<V>.distinctV(): List<V> {
        val out = ArrayList<V>()
        for (p in this) if (out.none { abs(it.x - p.x) < 1e-6 && abs(it.y - p.y) < 1e-6 && abs(it.z - p.z) < 1e-6 }) out.add(p)
        return out
    }
    private fun d(vararg x: Double) = x

    private fun snubCube(): List<V> {
        val out = ArrayList<V>()
        for (sx in listOf(1.0, -1.0)) for (sy in listOf(1.0, -1.0)) for (sz in listOf(1.0, -1.0)) {
            val minus = listOf(sx, sy, sz).count { it < 0 }
            val s = d(sx, sy / TRIBONACCI, sz * TRIBONACCI)
            out += if (minus % 2 == 0) cyclic(s) else swapped(s)
        }
        return out.distinctV()
    }
    private fun snubDodeca(): List<V> {
        val r = sqrt(PHI - 5.0 / 27.0)
        val x = Math.cbrt(PHI / 2 + r / 2) + Math.cbrt(PHI / 2 - r / 2)
        val a = x - 1 / x; val b = x * PHI + PHI * PHI + PHI / x
        val base = listOf(d(2 * a, 2.0, 2 * b),
            d(a + b / PHI + PHI, -a * PHI + b + 1 / PHI, a / PHI + b * PHI - 1),
            d(-a / PHI + b * PHI + 1, -a + b / PHI - PHI, a * PHI + b - 1 / PHI),
            d(-a / PHI + b * PHI - 1, a - b / PHI - PHI, a * PHI + b + 1 / PHI),
            d(a + b / PHI - PHI, a * PHI - b + 1 / PHI, a / PHI + b * PHI + 1))
        val out = ArrayList<V>()
        for (p in base) for (sx in listOf(1.0, -1.0)) for (sy in listOf(1.0, -1.0)) for (sz in listOf(1.0, -1.0))
            if (listOf(sx, sy, sz).count { it > 0 } % 2 == 0) out += cyclic(d(sx * p[0], sy * p[1], sz * p[2]))
        return out.distinctV()
    }
    private fun icosa() = evenPerms(d(0.0, 1.0, PHI))
    private fun dodeca() = (signs(d(1.0, 1.0, 1.0)).map { V(it[0], it[1], it[2]) } + evenPerms(d(0.0, 1 / PHI, PHI))).distinctV()

    private fun dist(a: V, b: V) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))
    /** The pairs at the n-th shortest distance. 0 is a solid's edges; further ones give the star solids. */
    private fun pairs(v: List<V>, rank: Int = 0): List<Pair<V, V>> {
        val all = ArrayList<Pair<Double, Pair<V, V>>>()
        for (i in v.indices) for (j in i + 1 until v.size) all.add(dist(v[i], v[j]) to (v[i] to v[j]))
        val lengths = ArrayList<Double>()
        for (x in all.map { it.first }.sorted()) if (lengths.isEmpty() || x - lengths.last() > 1e-5) lengths.add(x)
        val want = lengths[rank.coerceAtMost(lengths.lastIndex)]
        return all.filter { abs(it.first - want) < 1e-5 }.map { it.second }
    }
    private fun scaled(p: V, k: Double) = V(p.x * k, p.y * k, p.z * k)

    /** Centres the shape and fits it inside the layer. */
    private fun fit(segs: List<Pair<V, V>>, radius: Double = 0.92): FloatArray {
        val pts = segs.flatMap { listOf(it.first, it.second) }
        val cx = pts.map { it.x }.average(); val cy = pts.map { it.y }.average(); val cz = pts.map { it.z }.average()
        val r = pts.maxOf { sqrt((it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) + (it.z - cz) * (it.z - cz)) }.coerceAtLeast(1e-6)
        val k = radius / r
        val out = FloatArray(segs.size * 6); var i = 0
        for ((a, b) in segs) {
            out[i++] = ((a.x - cx) * k).toFloat(); out[i++] = ((a.y - cy) * k).toFloat(); out[i++] = ((a.z - cz) * k).toFloat()
            out[i++] = ((b.x - cx) * k).toFloat(); out[i++] = ((b.y - cy) * k).toFloat(); out[i++] = ((b.z - cz) * k).toFloat()
        }
        return out
    }
    private fun solid(v: List<V>, rank: Int = 0) = fit(pairs(v, rank))

    private fun ring(n: Int, z: Double, r: Double = 1.0, turn: Double = 0.0) = (0 until n).map { V(r * cos(turn + TAU * it / n), r * sin(turn + TAU * it / n), z) }
    private fun prism(n: Int): List<V> { val h = sin(PI / n); return ring(n, h) + ring(n, -h) }
    private fun antiprism(n: Int): List<V> {
        val side = 2 * sin(PI / n); val across = 2 * sin(PI / (2 * n))
        val h = sqrt(side * side - across * across) / 2
        return ring(n, h) + ring(n, -h, 1.0, PI / n)
    }
    private fun apexHeight(n: Int): Double { val side = 2 * sin(PI / n); return sqrt(side * side - 1) }

    /** Lines on a surface: rings one way and rings the other. */
    private fun wires(us: Int, vs: Int, closeU: Boolean, closeV: Boolean, at: (Double, Double) -> V): FloatArray {
        val out = ArrayList<Pair<V, V>>()
        val du = if (closeU) 1.0 / us else 1.0 / (us - 1)
        val dv = if (closeV) 1.0 / vs else 1.0 / (vs - 1)
        for (i in 0 until us) for (j in 0 until vs) {
            val u = i * du; val w = j * dv
            if (closeU || i < us - 1) out.add(at(u, w) to at(u + du, w))
            if (closeV || j < vs - 1) out.add(at(u, w) to at(u, w + dv))
        }
        return fit(out)
    }

    /** A shape in n dimensions, looked at along three directions spread evenly through them. */
    private fun project(p: DoubleArray): V {
        val n = p.size
        var x = 0.0; var y = 0.0; var z = 0.0
        for (i in 0 until n) {
            val a = TAU * i / n
            x += p[i] * cos(a); y += p[i] * sin(a); z += p[i] * cos(2 * a + 0.7)
        }
        return V(x, y, z)
    }
    private fun hypercube(n: Int): FloatArray {
        val out = ArrayList<Pair<V, V>>()
        fun corner(bits: Int) = project(DoubleArray(n) { if ((bits shr it) and 1 == 1) 1.0 else -1.0 })
        for (bits in 0 until (1 shl n)) for (k in 0 until n) if ((bits shr k) and 1 == 0) out.add(corner(bits) to corner(bits or (1 shl k)))
        return fit(out)
    }
    private fun simplex(n: Int): FloatArray {
        val v = (0..n).map { i -> project(DoubleArray(n + 1) { if (it == i) 1.0 else 0.0 }) }
        return fit((0..n).flatMap { i -> (i + 1..n).map { j -> v[i] to v[j] } })
    }
    private fun cross(n: Int): FloatArray {
        val v = (0 until n).flatMap { i -> listOf(1.0, -1.0).map { s -> project(DoubleArray(n) { if (it == i) s else 0.0 }) } }
        val out = ArrayList<Pair<V, V>>()
        // every pair is joined, except a point and its exact opposite
        for (i in v.indices) for (j in i + 1 until v.size) if (i / 2 != j / 2) out.add(v[i] to v[j])
        return fit(out)
    }

    /* flat figures */
    private fun p2(x: Double, y: Double) = V(x, y, 0.0)
    private fun circle(out: MutableList<Pair<V, V>>, cx: Double, cy: Double, r: Double, n: Int = 56) {
        for (i in 0 until n) out.add(p2(cx + r * cos(TAU * i / n), cy + r * sin(TAU * i / n)) to p2(cx + r * cos(TAU * (i + 1) / n), cy + r * sin(TAU * (i + 1) / n)))
    }
    private fun star(out: MutableList<Pair<V, V>>, n: Int, jump: Int, r: Double, turn: Double = PI / 2) {
        val c = (0 until n).map { p2(r * cos(turn + TAU * it / n), r * sin(turn + TAU * it / n)) }
        for (i in 0 until n) out.add(c[i] to c[(i + jump) % n])
    }
    private fun tri(out: MutableList<Pair<V, V>>, y: Double, half: Double, height: Double) {
        val a = p2(0.0, y); val b = p2(-half, y - height); val c = p2(half, y - height)
        out.add(a to b); out.add(b to c); out.add(c to a)
    }
    private fun flat(name: String): FloatArray? {
        val o = ArrayList<Pair<V, V>>()
        when (name) {
            "Vesica piscis" -> { circle(o, -0.42, 0.0, 0.84, 72); circle(o, 0.42, 0.0, 0.84, 72); o.add(p2(0.0, -0.73) to p2(0.0, 0.73)); o.add(p2(-0.42, 0.0) to p2(0.42, 0.0)) }
            "Hexagram" -> { star(o, 3, 1, 0.92); star(o, 3, 1, 0.92, -PI / 2); circle(o, 0.0, 0.0, 0.92, 72) }
            "Pentagram" -> { star(o, 5, 2, 0.92); circle(o, 0.0, 0.0, 0.92, 72) }
            "Enneagram" -> {
                val c = (0 until 9).map { p2(0.92 * cos(PI / 2 - TAU * it / 9), 0.92 * sin(PI / 2 - TAU * it / 9)) }
                for ((a, b) in listOf(0 to 3, 3 to 6, 6 to 0, 1 to 4, 4 to 2, 2 to 8, 8 to 5, 5 to 7, 7 to 1)) o.add(c[a] to c[b])
                circle(o, 0.0, 0.0, 0.92, 72)
            }
            "Sri Yantra" -> {   // nine interlocking triangles, four pointing up and five down, inside circles and a square frame
                for ((y, h, t) in listOf(Triple(0.62, 0.7, 1.28), Triple(0.44, 0.62, 0.92), Triple(0.29, 0.5, 0.74), Triple(0.15, 0.36, 0.5))) tri(o, y, h, t)
                for ((y, h, t) in listOf(Triple(-0.72, 0.76, -1.34), Triple(-0.54, 0.64, -0.98), Triple(-0.38, 0.52, -0.76), Triple(-0.24, 0.38, -0.54), Triple(-0.09, 0.22, -0.28))) tri(o, y, h, t)
                circle(o, 0.0, 0.0, 0.8, 72); circle(o, 0.0, 0.0, 0.86, 72)
                val s = 0.95
                listOf(p2(-s, -s), p2(s, -s), p2(s, s), p2(-s, s)).let { q -> for (i in 0..3) o.add(q[i] to q[(i + 1) % 4]) }
            }
            "Tree of Life" -> {
                val n = listOf(0.0 to 1.0, 0.46 to 0.72, -0.46 to 0.72, 0.46 to 0.26, -0.46 to 0.26, 0.0 to 0.05, 0.46 to -0.26, -0.46 to -0.26, 0.0 to -0.5, 0.0 to -1.0)
                val paths = listOf(0 to 1, 0 to 2, 0 to 5, 1 to 2, 1 to 3, 1 to 5, 2 to 4, 2 to 5, 3 to 4, 3 to 5, 3 to 6, 4 to 5, 4 to 7, 5 to 6, 5 to 7, 5 to 8, 6 to 7, 6 to 8, 6 to 9, 7 to 8, 7 to 9, 8 to 9)
                for ((a, b) in paths) o.add(p2(n[a].first, n[a].second) to p2(n[b].first, n[b].second))
                for ((x, y) in n) circle(o, x, y, 0.11, 24)
            }
            "Phyllotaxis" -> {   // seeds at the golden angle, as in a sunflower
                val golden = PI * (3 - sqrt(5.0))
                for (i in 1..180) { val r = 0.07 * sqrt(i.toDouble()); circle(o, r * cos(i * golden), r * sin(i * golden), 0.012 + 0.0016 * sqrt(i.toDouble()), 8) }
            }
            "Fibonacci squares" -> {   // squares growing by the Fibonacci numbers, with the spiral through them
                var x = 0.0; var y = 0.0; var a = 1; var b = 1; var dir = 0
                repeat(8) {
                    val s = a.toDouble()
                    val (x0, y0) = when (dir) { 0 -> x to y; 1 -> x - s to y; 2 -> x - s to y - s; else -> x to y - s }
                    val q = listOf(p2(x0, y0), p2(x0 + s, y0), p2(x0 + s, y0 + s), p2(x0, y0 + s))
                    for (i in 0..3) o.add(q[i] to q[(i + 1) % 4])
                    val cx = when (dir) { 0, 3 -> x0 else -> x0 + s }; val cy = when (dir) { 0, 1 -> y0 else -> y0 + s }
                    val start = dir * PI / 2
                    for (k in 0 until 12) o.add(p2(cx + s * cos(start + k * PI / 24), cy + s * sin(start + k * PI / 24)) to p2(cx + s * cos(start + (k + 1) * PI / 24), cy + s * sin(start + (k + 1) * PI / 24)))
                    when (dir) { 0 -> { x = x0 + s; y = y0 + s }; 1 -> { x = x0; y = y0 + s }; 2 -> { x = x0; y = y0 }; else -> { x = x0 + s; y = y0 } }
                    val c = a + b; a = b; b = c; dir = (dir + 1) % 4
                }
            }
            else -> return null
        }
        return fit(o)
    }

    fun of(name: String, dim: Int = 0): FloatArray? {
        flat(name)?.let { return it }
        PRISMS[name]?.let { return solid(prism(it)) }
        ANTIPRISMS[name]?.let { return solid(antiprism(it)) }
        return when (name) {
            "Truncated tetrahedron" -> solid(signs(d(1.0, 1.0, 3.0)).filter { s -> s.count { it < 0 } % 2 == 0 }.flatMap { cyclic(it) + swapped(it) }.distinctV())
            "Cuboctahedron" -> solid(allPerms(d(1.0, 1.0, 0.0)))
            "Truncated cube" -> solid(allPerms(d(R2 - 1, 1.0, 1.0)))
            "Truncated octahedron" -> solid(allPerms(d(0.0, 1.0, 2.0)))
            "Rhombicuboctahedron" -> solid(allPerms(d(1.0, 1.0, 1 + R2)))
            "Truncated cuboctahedron" -> solid(allPerms(d(1.0, 1 + R2, 1 + 2 * R2)))
            "Snub cube" -> solid(snubCube())
            "Icosidodecahedron" -> solid((allPerms(d(0.0, 0.0, PHI)) + evenPerms(d(0.5, PHI / 2, PHI * PHI / 2))).distinctV())
            "Truncated dodecahedron" -> solid(evenPerms(d(0.0, 1 / PHI, 2 + PHI), d(1 / PHI, PHI, 2 * PHI), d(PHI, 2.0, PHI + 1)))
            "Truncated icosahedron" -> solid(evenPerms(d(0.0, 1.0, 3 * PHI), d(1.0, 2 + PHI, 2 * PHI), d(PHI, 2.0, 2 * PHI + 1)))
            "Rhombicosidodecahedron" -> solid(evenPerms(d(1.0, 1.0, PHI * PHI * PHI), d(PHI * PHI, PHI, 2 * PHI), d(2 + PHI, 0.0, PHI * PHI)))
            "Truncated icosidodecahedron" -> solid(evenPerms(d(1 / PHI, 1 / PHI, 3 + PHI), d(2 / PHI, PHI, 1 + 2 * PHI), d(1 / PHI, PHI * PHI, 3 * PHI - 1), d(2 * PHI - 1, 2.0, 2 + PHI), d(PHI, 3.0, 2 * PHI)))
            "Snub dodecahedron" -> solid(snubDodeca())
            // star solids: the same outer corners as a regular solid, with longer chords. As line drawings two would match,
            // so each also shows the small solid or pits at its heart that give it its shape.
            "Small stellated dodecahedron" -> fit(pairs(icosa(), 1))
            "Great stellated dodecahedron" -> fit(pairs(dodeca(), 3))
            "Great icosahedron" -> fit(pairs(icosa(), 1) + pairs(icosa().map { scaled(it, 0.24) }))
            "Great dodecahedron" -> {
                val v = icosa(); val e = pairs(v); val side = dist(e[0].first, e[0].second)
                val pits = ArrayList<Pair<V, V>>()
                for (i in v.indices) for (j in i + 1 until v.size) for (k in j + 1 until v.size)
                    if (abs(dist(v[i], v[j]) - side) < 1e-5 && abs(dist(v[j], v[k]) - side) < 1e-5 && abs(dist(v[i], v[k]) - side) < 1e-5) {
                        val c = V((v[i].x + v[j].x + v[k].x) * 0.16, (v[i].y + v[j].y + v[k].y) * 0.16, (v[i].z + v[j].z + v[k].z) * 0.16)
                        pits.add(v[i] to c); pits.add(v[j] to c); pits.add(v[k] to c)
                    }
                fit(e + pits)
            }
            "Square pyramid" -> solid(signs(d(1.0, 1.0)).map { V(it[0], it[1], 0.0) } + V(0.0, 0.0, R2))
            "Pentagonal pyramid" -> solid(ring(5, 0.0) + V(0.0, 0.0, apexHeight(5)))
            "Triangular bipyramid" -> solid(ring(3, 0.0) + V(0.0, 0.0, apexHeight(3)) + V(0.0, 0.0, -apexHeight(3)))
            "Pentagonal bipyramid" -> solid(ring(5, 0.0) + V(0.0, 0.0, apexHeight(5)) + V(0.0, 0.0, -apexHeight(5)))
            "Square cupola" -> solid(signs(d(1 + R2, 1.0)).map { V(it[0], it[1], 0.0) } + signs(d(1.0, 1 + R2)).map { V(it[0], it[1], 0.0) } + signs(d(1.0, 1.0)).map { V(it[0], it[1], R2) })
            "Elongated square bipyramid" -> solid(signs(d(1.0, 1.0, 1.0)).map { V(it[0], it[1], it[2]) } + V(0.0, 0.0, 1 + R2) + V(0.0, 0.0, -1 - R2))
            "Sphere" -> wires(24, 13, true, false) { u, w -> val a = TAU * u; val b = PI * w; V(sin(b) * cos(a), sin(b) * sin(a), cos(b)) }
            "Cylinder" -> wires(24, 7, true, false) { u, w -> V(0.7 * cos(TAU * u), 0.7 * sin(TAU * u), 2 * w - 1) }
            "Cone" -> wires(24, 7, true, false) { u, w -> V(w * cos(TAU * u), w * sin(TAU * u), 1 - 2 * w) }
            "Möbius strip" -> wires(60, 5, true, false) { u, w ->
                val a = TAU * u; val s = 0.42 * (2 * w - 1)
                V((1 + s * cos(a / 2)) * cos(a), (1 + s * cos(a / 2)) * sin(a), s * sin(a / 2))
            }
            "Klein bottle" -> wires(36, 20, true, true) { u, w ->
                val a = TAU * u; val b = TAU * w; val r = 2.0
                val q = r + cos(a / 2) * sin(b) - sin(a / 2) * sin(2 * b)
                V(q * cos(a), q * sin(a), sin(a / 2) * sin(b) + cos(a / 2) * sin(2 * b))
            }
            "Trefoil knot" -> {
                val n = 180; val at = { i: Int -> val a = TAU * i / n; V(sin(a) + 2 * sin(2 * a), cos(a) - 2 * cos(2 * a), -sin(3 * a)) }
                fit((0 until n).map { at(it) to at(it + 1) })
            }
            HYPERCUBE -> hypercube(dimOf(name, dim.toFloat()))
            SIMPLEX -> simplex(dimOf(name, dim.toFloat()))
            CROSS -> cross(dimOf(name, dim.toFloat()))
            else -> null
        }
    }
}
