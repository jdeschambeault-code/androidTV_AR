package com.starshine.launcher

import java.io.File

const val LANDSCAPES = "Landscapes"

/**
 * Real landscapes: a photograph (Wikimedia Commons, openly licensed, see recordings/bg-review/ATTRIBUTION.md) brought to life
 * with small motions so it doesn't read as a still picture on the TV:
 *  - a slow, hidden noise layer gently warps the photo: bamboo and trees sway, water shimmers;
 *  - per theme, a visible layer on top: drifting mist, twinkling stars, rain.
 * The photos are copied to the device by the launcher (Landscape backgrounds), not built into the app; a scene only shows
 * once its photo is there.
 */
object Landscapes {
    const val DIR = "/storage/emulated/0/Android/data/com.starshine.launcher/files/landscapes"

    private fun photo(file: String, warp: Float) =
        Layer(1, "Media file", mapOf("blend" to 2f, "fit" to 0f, "warpBy" to 2f, "warpAmt" to warp), "$DIR/$file")
    /** The hidden source of the sway: soft noise, far too slow to be seen as motion of its own. */
    private fun sway(speed: Float = 0.22f, size: Float = 2.2f) = Layer(2, "Clouds", mapOf("show" to 0f, "speed" to speed, "scale" to size, "sat" to 0f, "detail" to 0.2f, "spin" to 0f))
    private fun mist(opacity: Float, speed: Float = 0.35f) = Layer(3, "Clouds", mapOf("blend" to 1f, "opacity" to opacity, "speed" to speed, "scale" to 1.6f, "sat" to 0f, "fade" to 0.3f, "detail" to 0.3f, "spin" to 0f))
    private fun stars(opacity: Float) = Layer(3, "Starfield", mapOf("blend" to 0f, "opacity" to opacity, "detail" to 0.3f, "speed" to 0.15f, "spin" to 0f))
    /** Sunlight through the leaves: soft beams from above that flicker slowly as the canopy moves. */
    private fun rays(opacity: Float) = Layer(4, "Light rays", mapOf("blend" to 1f, "opacity" to opacity, "speed" to 0.7f, "detail" to 0.5f, "glow" to 1f, "spin" to 0f))

    private fun scene(name: String, vararg layers: Layer) = BgScene(name, LANDSCAPES, layers.toList())

    val ALL: List<BgScene> = listOf(
        scene("Bamboo grove", photo("bamboo-01.jpg", 0.018f), sway(), mist(0.12f), rays(0.8f)),
        scene("Arashiyama bamboo", photo("bamboo-02.jpg", 0.016f), sway(), mist(0.18f, 0.25f), rays(0.9f)),
        scene("Bamboo trail", photo("bamboo-03.jpg", 0.014f), sway(0.18f), rays(0.7f)),
        scene("Bamboo garden", photo("bamboo-04.jpg", 0.018f), sway(), mist(0.1f), rays(0.7f)),
        scene("Tree in the fog", photo("misty-forest-05.jpg", 0.008f), sway(0.15f, 3f), mist(0.35f, 0.2f)),
        scene("Morning forest", photo("misty-forest-06.jpg", 0.01f), sway(0.2f), mist(0.22f, 0.25f), rays(0.9f)),
        scene("Sea of clouds", photo("misty-forest-07.jpg", 0.006f), sway(0.12f, 3f), mist(0.3f, 0.3f)),
        scene("Waterfall in the ferns", photo("waterfall-08.jpg", 0.012f), sway(0.3f)),
        scene("Beauchamp Falls", photo("waterfall-09.jpg", 0.012f), sway(0.3f)),
        scene("Rainforest stream", photo("waterfall-10.jpg", 0.012f), sway(0.28f), mist(0.1f), rays(0.6f)),
        scene("Mirror lake", photo("lake-11.jpg", 0.006f), sway(0.18f, 3f)),
        scene("Mountain reflection", photo("lake-12.jpg", 0.006f), sway(0.18f, 3f), mist(0.08f)),
        scene("Frosty lake", photo("lake-13.jpg", 0.006f), sway(0.15f, 3f), mist(0.2f, 0.18f)),
        scene("Ryōan-ji garden", photo("zen-14.jpg", 0.004f), sway(0.15f, 3f)),
        scene("Japanese garden", photo("zen-15.jpg", 0.01f), sway(0.2f)),
        scene("Bali sunset", photo("beach-16.jpg", 0.008f), sway(0.2f, 3f)),
        scene("Granite beach", photo("beach-17.jpg", 0.008f), sway(0.2f, 3f), mist(0.08f)),
        scene("Harbour sunset", photo("beach-18.jpg", 0.008f), sway(0.2f, 3f)),
        scene("Northern lights, Mývatn", photo("aurora-19.jpg", 0.012f), sway(0.12f, 3f), stars(0.35f)),
        scene("Aurora over Abisko", photo("aurora-20.jpg", 0.014f), sway(0.12f, 3f), stars(0.3f))
    )

    /** The scenes whose photo is on this device. */
    fun available(): List<BgScene> = ALL.filter { s -> s.layers.all { it.media.isEmpty() || File(it.media).exists() } }
}
