import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Personal settings stay in local.properties (not committed); everyone else gets the empty defaults.
// Published APKs are built with STARSHINE_PUBLIC=1, which ignores them so no home-network details get compiled in.
val personal = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists() && System.getenv("STARSHINE_PUBLIC") == null) f.inputStream().use { load(it) }
}
fun personal(key: String) = "\"" + (personal.getProperty("starshine.$key") ?: "") + "\""

// HELP.md and its pictures are the same files GitHub shows; the app gets a copy at build time.
val copyHelp = tasks.register<Copy>("copyHelp") {
    from(rootProject.file("../../HELP.md"))
    from(rootProject.file("../../docs/images")) { into("images") }
    into(layout.buildDirectory.dir("generated/helpAssets/help"))
}

android {
    namespace = "com.starshine.launcher"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.starshine.launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 57
        versionName = "2.7.0"
        buildConfigField("String", "JELLYFIN", personal("jellyfin"))
        buildConfigField("String", "PRIVATE_DOMAIN", personal("privateDomain"))
        buildConfigField("String", "WEB_TILES", personal("webTiles"))
        buildConfigField("String", "BLISS", personal("bliss"))
    }
    buildTypes {
        // a debuggable app runs several times slower, which the audio on this small device can't afford
        debug { isDebuggable = false }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.compose.ui.ExperimentalComposeUiApi", "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi")
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/helpAssets"))
}

tasks.named("preBuild") { dependsOn(copyHelp) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
}
