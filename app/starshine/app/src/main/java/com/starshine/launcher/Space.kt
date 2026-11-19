package com.starshine.launcher

/**
 * 3D scenes: instead of flat layers stacked on the screen, elements placed in one 3D space and seen through a camera.
 * A 3D scene is a [BgScene] with mode 1, so it saves, applies, animates, joins journeys and goes through the Render tab
 * like any other scene. Its "layers" are elements: a camera (always first), native 3D elements (sun, stars, planets…),
 * 3D objects (the sacred geometry and solids as real wire objects), and panels (any flat background on a screen in space).
 *
 * Interaction: the first Sun is the light of the scene. Planets are lit from where it is, and it throws a lens flare when
 * the camera sees it.
 */
const val SPACE_GROUP = "3D scenes"
const val CAMERA = "Camera"
const val OBJ_PREFIX = "3D: "
const val PANEL_PREFIX = "Panel: "

object Space {
    val NATIVE = listOf("Sun", "Stars", "Dust", "Glow orbs", "Galaxy", "Asteroid ring", "Nebula cloud", "Planet") +
        SpaceShapes.WORLDS.keys + SpaceShapes.PARTICLES.keys + SpaceShapes.PLANTS.keys + SpaceShapes.TESSERACT + Particles.TYPE

    private fun num(v: Float) = String.format(java.util.Locale.US, "%.2f", v)
    val TX = BgParams.P("tx", "Looks at X", 0f, 0.1f, -1000f, 1000f, ::num)
    val TY = BgParams.P("ty", "Looks at Y", 0f, 0.1f, -1000f, 1000f, ::num)
    val TZ = BgParams.P("tz", "Looks at Z", 0f, 0.1f, -1000f, 1000f, ::num)
    val FOV = BgParams.P("fov", "Field of view", 60f, 2f, 10f, 140f, { "${Math.round(it)}°" })
    val ORBIT = BgParams.P("orbit", "Orbits around", 0f, 0.5f, -60f, 60f, { if (it == 0f) "still" else String.format(java.util.Locale.US, "%.1f°/s", it) })
    val FLARE = BgParams.P("flare", "Lens flare", 0.6f, 0.1f, 0f, 3f)
    val PARAMS = listOf(TX, TY, TZ, FOV, ORBIT, FLARE)

    /* settings made for 3D elements (the flat layers' Basics don't fit them) */
    private fun pct(v: Float) = "${Math.round(v * 100)}%"
    val CORONA = BgParams.P("corona", "Corona", 1f, 0.1f, 0.1f, 5f, ::pct)
    val RAYS = BgParams.P("rays", "Rays", 12f, 1f, 0f, 48f, { "${Math.round(it)}" })
    val RAYLEN = BgParams.P("rayLen", "Ray length", 1f, 0.1f, 0f, 3f, ::pct)
    val RAYSPIN = BgParams.P("raySpin", "Rays turn", 3f, 1f, -90f, 90f, { "${Math.round(it)}°/s" })
    val HUE2 = BgParams.P("hue2", "Band colour shift", 30f, 10f, -180f, 180f, { "${Math.round(it)}°" })
    val BANDS = BgParams.P("bands", "Bands", 9f, 1f, 0f, 40f, { "${Math.round(it)}" })
    val ATMO = BgParams.P("atmo", "Atmosphere", 1f, 0.1f, 0f, 4f, ::pct)
    val PSIZE = BgParams.P("psize", "Particle size", 1f, 0.1f, 0.1f, 8f, ::pct)
    val HVAR = BgParams.P("hvar", "Colour variety", 1f, 0.1f, 0f, 5f, ::pct)
    val TWINK = BgParams.P("twink", "Twinkle", 1f, 0.1f, 0f, 3f, ::pct)
    /** Same settings as the flat layers use, named for what they do here. */
    val COUNT = BgParams.P("detail", "Amount", 0.5f, 0.05f, 0f, 1f, ::pct)
    val WIDTH = BgParams.P("detail", "Line width", 0.5f, 0.05f, 0f, 1f, ::pct)
    val TURN = BgParams.P("spin", "Keeps turning", 0f, 0.01f, -2f, 2f, { String.format(java.util.Locale.US, "%.2f", it) })
    val DRIFT = BgParams.P("speed", "Drift speed", 1f, 0.1f, -5f, 10f, { String.format(java.util.Locale.US, "%.1fx", it) })
    val ELEMENT_PARAMS = listOf(CORONA, RAYS, RAYLEN, RAYSPIN, HUE2, BANDS, ATMO, PSIZE, HVAR, TWINK)

    /** What a 3D element shows under "Element", in order. Camera and panels keep their own sections. */
    fun attrs(t: String): List<BgParams.P> = when {
        t == Particles.TYPE -> Particles.EMITTER + Particles.FIELDS + Particles.LOOKING
        t == "Sun" -> listOf(BgParams.SIZE, BgParams.GLOW, BgParams.HUE, BgParams.SAT, CORONA, RAYS, RAYLEN, RAYSPIN, FLARE, BgParams.OPACITY)
        SpaceShapes.isWorld(t) -> listOf(BgParams.SIZE, BgParams.HUE, HUE2, BgParams.SAT, BANDS, ATMO, BgParams.GLOW, BgParams.OPACITY)
        isObject(t) || SpaceShapes.isLines(t) -> listOf(BgParams.SIZE, WIDTH, BgParams.GLOW, BgParams.HUE, BgParams.SAT) + (if (t == SpaceShapes.TESSERACT) listOf(BgParams.SPEED) else emptyList()) + BgParams.OPACITY
        t in NATIVE -> listOf(COUNT, BgParams.SIZE, PSIZE, BgParams.GLOW, BgParams.HUE, HVAR, BgParams.SAT, TWINK) + (if (t == "Dust") listOf(DRIFT) else emptyList()) + BgParams.OPACITY
        else -> emptyList()
    }
    /** Keys an animation preset may move on this element: its own settings, plus where it is. */
    fun animKeys(t: String): Set<String> = (attrs(t).map { it.key } + listOf("x", "y", "z", "tiltX", "tiltY", "angle", "hueDrift")).toSet()

    /** Every element there is, from the flat generators: [flat] is passed in so the two catalogs never build each other. */
    fun elements(flat: List<Generator>): List<Generator> =
        listOf(Generator(CAMERA, GenKind.Space)) + NATIVE.map { Generator(it, GenKind.Space) } +
            flat.filter { it.kind == GenKind.Line || it.kind == GenKind.Solid }.map { Generator(OBJ_PREFIX + it.name, GenKind.Space) } +
            flat.filter { it.kind == GenKind.Screen || it.kind == GenKind.Classic || it.kind == GenKind.Canvas }.map { Generator(PANEL_PREFIX + it.name, GenKind.Space) }

    fun isObject(t: String) = t.startsWith(OBJ_PREFIX)
    fun isPanel(t: String) = t.startsWith(PANEL_PREFIX)
    fun inner(t: String) = t.removePrefix(OBJ_PREFIX).removePrefix(PANEL_PREFIX)
    fun short(t: String) = inner(t).removePrefix(CLASSIC_PREFIX).removePrefix(LINES_PREFIX)

    /** Groups of the Add element list. */
    val ADD_GROUPS: List<Pair<String, (Generator) -> Boolean>> = listOf(
        "Particles (emitter + fields)" to { g -> g.name == Particles.TYPE },
        "Light & sky" to { g -> g.name in listOf("Sun", "Stars", "Galaxy", "Nebula cloud", "Glow orbs") },
        "Worlds" to { g -> SpaceShapes.isWorld(g.name) || g.name == "Asteroid ring" },
        "Elements & weather" to { g -> g.name in SpaceShapes.PARTICLES || g.name == "Dust" },
        "Nature & crystals" to { g -> g.name in SpaceShapes.PLANTS },
        "Dimensions" to { g -> g.name == SpaceShapes.TESSERACT },
        "3D objects" to { g -> isObject(g.name) },
        "Flat panels in space" to { g -> isPanel(g.name) }
    )

    /** Where a new element appears, and how it looks to start with. */
    fun defaults(t: String): Map<String, Float> = when {
        // the world's centre is 5 m in front of the camera; elements appear there, sized to be seen whole from it
        t == CAMERA -> mapOf("z" to 5f, "y" to 0f)
        t == "Sun" -> mapOf("scale" to 0.8f, "hue" to 35f, "sat" to 0.8f)
        t == Particles.TYPE -> Particles.DEFAULTS
        t == "Stars" -> mapOf("detail" to 0.6f, "hue" to 210f, "sat" to 0.25f)
        t == "Planet" -> mapOf("scale" to 1f, "hue" to 200f)
        t == "Galaxy" -> mapOf("tiltX" to 60f, "hue" to 230f, "scale" to 0.3f)
        t == "Asteroid ring" -> mapOf("tiltX" to 75f, "hue" to 30f, "sat" to 0.3f, "scale" to 0.6f)
        t == "Nebula cloud" -> mapOf("hue" to 280f, "scale" to 0.4f, "opacity" to 0.7f)
        t == "Glow orbs" -> mapOf("hue" to 180f, "scale" to 0.5f)
        t in SpaceShapes.WORLDS -> SpaceShapes.WORLDS.getValue(t)
        t in SpaceShapes.PARTICLES -> SpaceShapes.PARTICLES.getValue(t)
        t in SpaceShapes.PLANTS -> SpaceShapes.PLANTS.getValue(t)
        t == SpaceShapes.TESSERACT -> mapOf("hue" to 270f, "sat" to 0.6f, "detail" to 0.25f, "glow" to 1.3f)
        isObject(t) -> mapOf("hue" to 190f, "sat" to 0.5f, "detail" to 0.3f)
        // a panel starts on the camera, filling its frame; "world" = 1 leaves it standing in the scene instead
        isPanel(t) -> mapOf("scale" to 1f, "spin" to 0f, "world" to 0f)
        else -> emptyMap()
    }

    fun camera() = Layer(1, CAMERA, defaults(CAMERA))

    /** The icon of an element: the picture of the layer it comes from, or the closest flat one. */
    fun iconName(t: String): String = when (t) {
        Particles.TYPE -> CLASSIC_PREFIX + "Fireflies"; "Sun" -> "Sun flare"; "Stars" -> "Starfield"; "Glow orbs" -> "Orbs"; "Galaxy" -> CLASSIC_PREFIX + "Slow galaxy"
        "Asteroid ring" -> CLASSIC_PREFIX + "Wormhole gate"; "Nebula cloud" -> "Nebula"; "Planet" -> "Globe"
        in SpaceShapes.WORLDS -> "Globe"; "Ocean waves", "Fountain" -> "Water"; "Wind" -> LINES_PREFIX + "Flow"; "Embers" -> "Fire"
        "Rain" -> CLASSIC_PREFIX + "Soft rain"; "Snow" -> CLASSIC_PREFIX + "Snowfall"; "Fireflies" -> CLASSIC_PREFIX + "Fireflies"; "Smoke" -> "Clouds"
        "Lotus" -> "Floral mandala"; "Crystal cluster" -> "Octahedron"; SpaceShapes.TESSERACT -> "Hypercube"
        "Tree" -> "Phyllotaxis"; "Fern" -> "Golden spiral"
        else -> inner(t)
    }

    private fun L(id: Int, type: String, vararg p: Pair<String, Float>) = Layer(id, type, defaults(type) + mapOf(*p))
    /** A particle preset's settings, to start an element from. */
    private fun pe(name: String): Array<Pair<String, Float>> = Particles.PRESETS.first { it.first == name }.second.toList().toTypedArray()
    private fun anim(i: Int, key: String, mode: Int, amount: Float, seconds: Float, phase: Float = 0f, ease: Int = 1): Array<Pair<String, Float>> {
        val t = LayerAnim.TARGETS.indexOfFirst { it.key == key }.coerceAtLeast(0).toFloat()
        return arrayOf("a${i}t" to t, "a${i}m" to mode.toFloat(), "a${i}a" to amount, "a${i}d" to seconds, "a${i}e" to ease.toFloat(), "a${i}p" to phase)
    }

    /** Ready-made 3D scenes, shown in the 3D scenes tab and usable in journeys. */
    val SCENES: List<BgScene> by lazy { listOf(
        BgScene("Solar system", SPACE_GROUP, listOf(
            L(1, CAMERA, "z" to 16f, "y" to 4f, "orbit" to 3f),
            L(2, "Stars"),
            L(3, "Sun", "x" to 0f, "y" to 0f, "z" to 0f, "scale" to 2.5f, "flare" to 0.4f),
            // a planet on a circle: X and Z swing a quarter turn apart
            L(4, "Planet", "scale" to 0.8f, "hue" to 200f, *anim(0, "x", 4, 7f, 40f), *anim(1, "z", 4, 7f, 40f, 0.25f)),
            L(5, "Planet", "scale" to 0.45f, "hue" to 20f, *anim(0, "x", 4, 11f, 70f, 0.5f), *anim(1, "z", 4, 11f, 70f, 0.75f))
        ), mode = 1),
        BgScene("Star sea", SPACE_GROUP, listOf(
            L(1, CAMERA, "z" to 10f, "y" to 0f, *anim(0, "z", 2, -30f, 40f, ease = 0), *anim(1, "tz", 2, -30f, 40f, ease = 0)),
            L(2, "Stars", "detail" to 0.9f),
            L(3, "Dust", "scale" to 3f, "detail" to 0.8f, "hue" to 200f),
            L(4, "Nebula cloud", "z" to -25f, "scale" to 2.5f, "hue" to 260f),
            L(5, "Sun", "x" to -20f, "y" to 6f, "z" to -60f, "scale" to 4f, "hue" to 200f, "sat" to 0.4f)
        ), mode = 1),
        BgScene("Galaxy", SPACE_GROUP, listOf(
            L(1, CAMERA, "z" to 14f, "y" to 5f, "orbit" to 4f),
            L(2, "Stars", "detail" to 0.5f),
            L(3, "Galaxy", "scale" to 1.8f, "tiltX" to 0f, "detail" to 0.9f)
        ), mode = 1),
        BgScene("Sacred orbit", SPACE_GROUP, listOf(
            L(1, CAMERA, "z" to 9f, "y" to 1f, "orbit" to 5f),
            L(2, "Stars"),
            L(3, OBJ_PREFIX + "Metatron's Cube", "scale" to 3f, "hue" to 45f, "spin" to 0.01f),
            L(4, OBJ_PREFIX + "Dodecahedron", "scale" to 1.4f, "hue" to 190f, "spin" to 0.04f, "glow" to 1.4f),
            L(5, "Glow orbs", "scale" to 1.2f, "opacity" to 0.6f),
            L(6, "Sun", "x" to 0f, "y" to 0f, "z" to -25f, "scale" to 3f, "flare" to 0.3f)
        ), mode = 1),
        BgScene("Ringed world", SPACE_GROUP, listOf(
            L(1, CAMERA, "z" to 9f, "y" to 2f, "orbit" to 2f),
            L(2, "Stars"),
            L(3, "Planet", "scale" to 2f, "hue" to 35f, "sat" to 0.6f),
            L(4, "Asteroid ring", "scale" to 1.2f),
            L(5, "Sun", "x" to 25f, "y" to 6f, "z" to -10f, "scale" to 3f)
        ), mode = 1)
    ) + CONVERTED }

    /** A setting that keeps turning, [deg] degrees a second: the "Keeps turning" expression. */
    private fun Layer.turn(key: String, deg: Float) = Expr.set(this, key, "v + a*t", deg, 1f)
    private fun cam(orbit: Float = 2f, vararg p: Pair<String, Float>) = L(1, CAMERA, "orbit" to orbit, *p)
    private fun o(id: Int, shape: String, vararg p: Pair<String, Float>) = L(id, OBJ_PREFIX + shape, *p)

    /**
     * Every built-in background, rebuilt as a 3D scene: the same idea with real 3D elements (a star sphere for the
     * starfield, the lit sun for the flare, ocean waves for water, embers and smoke for fire, solids turning in space).
     * Only Iris and Honeycomb, which have no 3D form, stay as panels in front of the camera.
     */
    private val CONVERTED: List<BgScene> by lazy { listOf(
        BgScene("Default 3D", SPACE_GROUP, listOf(cam(2f), L(2, "Stars"),
            L(3, "Sun", "z" to -3f, "scale" to 1.2f, "hue" to 30f, "flare" to 0.4f),
            o(4, "Dodecahedron", "scale" to 1.2f, "hue" to 190f, "sat" to 0.25f, "detail" to 0.75f, "glow" to 0.9f).turn("tiltY", 10f)), mode = 1),
        BgScene("Temple of Light 3D", SPACE_GROUP, listOf(cam(3f), L(2, "Stars", "detail" to 0.4f), L(3, "Glow orbs", "hue" to 180f, "opacity" to 0.5f),
            o(4, "Flower of Life", "z" to -1f, "scale" to 2.2f, "hue" to 40f, "sat" to 0.6f, "opacity" to 0.7f, "detail" to 0.25f).turn("angle", 2f),
            o(5, "Dodecahedron", "hue" to 30f, "sat" to 0.35f, "glow" to 1.4f).turn("tiltY", 12f)), mode = 1),
        BgScene("Flower bloom 3D", SPACE_GROUP, listOf(cam(2f), L(2, "Nebula cloud", "z" to -4f, "hue" to 250f, "opacity" to 0.6f),
            o(3, "Flower of Life", "scale" to 1.8f, "hue" to 200f, "hueDrift" to 3f, "detail" to 0.3f).turn("angle", 4f),
            o(4, "Seed of Life", "scale" to 0.9f, "hue" to 40f, "sat" to 0.5f).turn("angle", -8f),
            L(5, "Lotus", "y" to -1.2f, "scale" to 0.8f)), mode = 1),
        BgScene("Metatron 3D", SPACE_GROUP, listOf(cam(2f), L(2, "Stars"), L(3, "Nebula cloud", "z" to -5f, "hue" to 200f),
            o(4, "Metatron's Cube", "scale" to 2f, "hue" to 180f, "sat" to 0.5f, "detail" to 0.22f).turn("tiltY", 5f),
            o(5, "Star tetrahedron", "scale" to 0.8f, "hue" to 45f, "sat" to 0.7f, "glow" to 1.3f).turn("tiltY", -15f)), mode = 1),
        BgScene("Floral mandala 3D", SPACE_GROUP, listOf(cam(1.5f),
            o(2, "Floral mandala", "scale" to 2.2f, "hue" to 300f, "hueDrift" to 2f, "detail" to 0.25f).turn("angle", 2f),
            o(3, "Floral mandala", "scale" to 1.1f, "hue" to 40f, "detail" to 0.2f).turn("angle", -5f),
            L(4, "Sun", "z" to -4f, "scale" to 0.5f, "hue" to 0f), L(5, "Fireflies", "scale" to 1.5f)), mode = 1),
        BgScene("Water 3D", SPACE_GROUP, listOf(cam(2f, "y" to 1f), L(2, "Stars"), L(3, "Ocean waves", "y" to -1f, "scale" to 1.6f),
            o(4, "Dodecahedron", "y" to 0.5f, "hue" to 190f, "sat" to 0.25f, "detail" to 0.75f, "glow" to 0.9f).turn("tiltY", 8f),
            L(5, "Sun", "x" to 3f, "y" to 2f, "z" to -8f, "scale" to 0.7f, "hue" to 200f, "sat" to 0.4f)), mode = 1),
        BgScene("Air 3D", SPACE_GROUP, listOf(cam(2f), L(2, "Stars", "detail" to 0.3f), L(3, "Wind", "scale" to 1.5f), L(4, "Smoke", "opacity" to 0.4f),
            o(5, "Octahedron", "scale" to 1.2f, "hue" to 210f, "sat" to 0.2f, "glow" to 1.6f).turn("tiltY", 9f)), mode = 1),
        BgScene("Fire 3D", SPACE_GROUP, listOf(cam(2f), L(2, "Embers", "y" to -0.5f, "scale" to 1.5f), L(3, "Smoke", "hue" to 20f, "opacity" to 0.3f, "y" to 0.5f),
            L(4, "Sun", "y" to -0.5f, "z" to -2f, "scale" to 0.9f, "hue" to 350f),
            o(5, "Star tetrahedron", "scale" to 1.2f, "hue" to 225f, "sat" to 0.4f, "glow" to 1.4f).turn("tiltY", 10f)), mode = 1),
        BgScene("Earth 3D", SPACE_GROUP, listOf(cam(2f), L(2, "Stars"), L(3, "Earth", "scale" to 1.4f).turn("tiltY", 6f),
            L(4, "Sun", "x" to 6f, "y" to 2f, "z" to -6f),
            o(5, "Cube", "scale" to 2.4f, "hue" to 45f, "sat" to 0.2f, "opacity" to 0.5f).turn("tiltY", 3f),
            // the moon goes round: X and Z swing a quarter turn apart
            L(6, "Moon", "scale" to 0.3f).let { Expr.set(Expr.set(it, "x", "v + a*sin(t)", 2.6f, 0.2f), "z", "v + a*cos(t)", 2.6f, 0.2f) }), mode = 1),
        BgScene("Ether 3D", SPACE_GROUP, listOf(cam(2f), L(2, "Nebula cloud", "hue" to 300f, "scale" to 0.6f), L(3, "Glow orbs", "opacity" to 0.6f),
            o(4, "Nested solids", "scale" to 1.4f, "sat" to 0.35f, "glow" to 1.5f, "hueDrift" to 4f).turn("tiltY", 7f),
            L(5, PANEL_PREFIX + "Honeycomb", "opacity" to 0.35f)), mode = 1),
        BgScene("Star temple 3D", SPACE_GROUP, listOf(cam(2f), L(2, "Stars", "detail" to 0.9f),
            L(3, "Galaxy", "z" to -15f, "scale" to 0.8f, "tiltX" to 70f),
            o(4, "Metatron's Cube", "scale" to 3f, "hue" to 200f, "sat" to 0.4f, "opacity" to 0.45f, "detail" to 0.2f).turn("angle", 1.5f)), mode = 1),
        BgScene("Nebula orbs 3D", SPACE_GROUP, listOf(cam(2f), L(2, "Stars"), L(3, "Nebula cloud", "hue" to 0f, "scale" to 0.7f),
            L(4, "Glow orbs", "scale" to 0.8f, "hue" to 180f, "glow" to 1.2f), L(5, "Dust", "opacity" to 0.7f)), mode = 1),
        BgScene("Warp gate 3D", SPACE_GROUP, listOf(
            // the camera flies forward through the gate, again and again
            L(1, CAMERA).let { Expr.set(Expr.set(it, "z", "v - a*fract(t/20)", 30f), "tz", "v - a*fract(t/20)", 30f) },
            L(2, "Stars", "detail" to 1f), L(3, "Dust", "scale" to 3f),
            o(4, "Torus", "z" to -10f, "scale" to 2f, "hue" to 170f, "detail" to 0.25f).turn("angle", 20f)), mode = 1),
        BgScene("Inner eye 3D", SPACE_GROUP, listOf(cam(0f), L(2, PANEL_PREFIX + "Iris", "opacity" to 0.9f),
            o(3, "Seed of Life", "scale" to 0.6f, "sat" to 0.3f, "detail" to 0.1f).turn("angle", 6f), L(4, "Fireflies")), mode = 1),
        BgScene("Sun 3D", SPACE_GROUP, listOf(cam(1.5f), L(2, "Stars"), L(3, "Sun", "scale" to 1.5f, "hue" to 40f, "rays" to 16f, "flare" to 0.8f),
            L(4, "Dust", "hue" to 40f, "opacity" to 0.5f)), mode = 1),
        BgScene("Candle 3D", SPACE_GROUP, listOf(cam(1f),
            L(2, "Sun", "y" to 0.5f, "scale" to 0.3f, "hue" to 30f, "corona" to 2f, "rays" to 0f, "flare" to 0.2f),
            L(3, "Embers", "y" to 0.2f, "scale" to 0.4f),
            o(4, "Flower of Life", "z" to -2f, "scale" to 2.5f, "hue" to 40f, "sat" to 0.5f, "opacity" to 0.2f, "detail" to 0.15f),
            L(5, "Fireflies", "opacity" to 0.5f)), mode = 1),
        BgScene("Hologram 3D", SPACE_GROUP, listOf(cam(4f),
            o(2, "Nested solids", "scale" to 1.3f, "sat" to 0.5f, "detail" to 0.6f, "glow" to 1.8f, "hueDrift" to 8f).turn("tiltY", 10f),
            L(3, PANEL_PREFIX + "Honeycomb", "opacity" to 0.45f, "hueDrift" to 6f),
            L(4, "Crystal cluster", "y" to -1.4f, "scale" to 0.7f, "hue" to 180f)), mode = 1),
        // universal particles (Particles.kt)
        BgScene("Particle fountain", SPACE_GROUP, listOf(cam(0f, "y" to 1f), L(2, "Stars"),
            L(3, Particles.TYPE, *pe("Fountain"), "y" to -1.5f, "scale" to 1f)), mode = 1),
        BgScene("Firefly forest", SPACE_GROUP, listOf(cam(0f), L(2, Particles.TYPE, *pe("Fireflies"), "scale" to 2.5f),
            L(3, "Fern", "x" to -2f, "y" to -1.5f, "scale" to 1.2f), L(4, "Tree", "x" to 2f, "y" to -1.5f, "scale" to 1.4f)), mode = 1),
        BgScene("Fractal snowfall", SPACE_GROUP, listOf(cam(0f), L(2, "Stars"), L(3, Particles.TYPE, *pe("Fractal snow"), "y" to 2f, "scale" to 2f),
            L(4, Particles.TYPE, *pe("Vortex"), "y" to -1f, "scale" to 1.2f, "peMax" to 800f)), mode = 1),
        // a ring of spinning sparks standing in front of you, throwing sparks that fall
        BgScene("Spark portal", SPACE_GROUP, listOf(cam(0f), L(2, "Stars"),
            L(3, Particles.TYPE, *pe("Spark ring"), "tiltX" to 90f, "scale" to 1.6f),
            L(4, Particles.TYPE, *pe("Flung sparks"), "tiltX" to 90f, "scale" to 1.6f)), mode = 1)
    ).map { s -> s.copy(layers = s.layers.map { if (it.type == CAMERA) stillCamera(it) else it }) } }

    /** Built-in 3D scenes start with a still camera: no orbit and no camera animation (the elements still move). Turn either on in the camera's settings. */
    private fun stillCamera(l: Layer) = l.copy(v = l.v.filterKeys { k -> !(k.length == 3 && k[0] == 'a' && k[1].isDigit()) } + ("orbit" to 0f))
}
