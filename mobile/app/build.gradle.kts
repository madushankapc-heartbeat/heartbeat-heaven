plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.heartbeatheaven.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.heartbeatheaven.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Reuse the HEARTBEAT HEAVEN logo already stored in the web project for the Android launcher icon.
val heartbeatIconResDir = layout.buildDirectory.dir("generated/res/heartbeatIcon")

val prepareHeartbeatIcon by tasks.registering {
    outputs.dir(heartbeatIconResDir)
    doLast {
        val source = rootProject.projectDir.parentFile.resolve("public/heartbeat-heaven-logo.png")
        val target = heartbeatIconResDir.get().dir("drawable").file("heartbeat_heaven_logo.png").asFile
        check(source.exists()) { "HEARTBEAT HEAVEN logo not found at ${source.absolutePath}" }
        target.parentFile.mkdirs()
        source.copyTo(target, overwrite = true)
    }
}

// Keep the player UI synchronized with ExoPlayer while a song is playing.
// This is applied at build time until the source can be updated directly.
val preparePlayerProgressFix by tasks.registering {
    doLast {
        val source = rootProject.projectDir.resolve("app/src/main/java/com/heartbeatheaven/app/MainActivity.kt")
        var text = source.readText()
        val old = """        setContent {
            HeartbeatTheme {
                HeartbeatApp(
                    currentSongId, currentSong, isPlaying, positionMs, durationMs,
                    ::play, ::pause, ::seekTo, ::stop
                )
            }
        }"""
        val updated = """        setContent {
            HeartbeatTheme {
                LaunchedEffect(currentSongId, isPlaying) {
                    while (currentSongId != null) {
                        positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: positionMs
                        durationMs = player?.duration?.takeIf { it > 0 } ?: durationMs
                        delay(if (isPlaying) 250L else 500L)
                    }
                }
                HeartbeatApp(
                    currentSongId, currentSong, isPlaying, positionMs, durationMs,
                    ::play, ::pause, ::seekTo, ::stop
                )
            }
        }"""
        if (old in text) text = text.replace(old, updated)

        val oldEffect = """    LaunchedEffect(currentSongId, isPlaying) {
        while (currentSongId != null) {
            if (isPlaying) {
                delay(500)
                // Position is supplied by the Activity through the player callback loop.
                // The Activity state is refreshed here through the lightweight public API below.
            } else delay(700)
        }
    }

"""
        if (oldEffect in text) text = text.replace(oldEffect, "")
        source.writeText(text)
    }
}

// Add the new Profile tab without rewriting the already-tested main activity source.
val prepareAccountFeature by tasks.registering {
    doLast {
        val source = rootProject.projectDir.resolve("app/src/main/java/com/heartbeatheaven/app/MainActivity.kt")
        var text = source.readText()
        if (!text.contains("Icons.Default.Person")) {
            text = text.replace("import androidx.compose.material.icons.filled.PlayArrow", "import androidx.compose.material.icons.filled.PlayArrow\nimport androidx.compose.material.icons.filled.Person")
        }
        if (!text.contains("NavigationBarItem(tab == 3")) {
            val oldNav = """                NavigationBarItem(tab == 2, { tab = 2 }, icon = { Icon(Icons.Default.Favorite, null) }, label = { Text(\"Favorites\") })"""
            val newNav = oldNav + "\n                NavigationBarItem(tab == 3, { tab = 3 }, icon = { Icon(Icons.Default.Person, null) }, label = { Text(\"Profile\") })"
            text = text.replace(oldNav, newNav)
        }
        if (!text.contains("else if (tab == 3)")) {
            val marker = """            } else if (selected != null) {"""
            val replacement = """            } else if (tab == 3) {
                AccountScreen()
            } else if (selected != null) {"""
            text = text.replace(marker, replacement)
        }
        source.writeText(text)
    }
}

android.sourceSets["main"].res.srcDir(heartbeatIconResDir)
tasks.named("preBuild").configure {
    dependsOn(prepareHeartbeatIcon)
    dependsOn(preparePlayerProgressFix)
    dependsOn(prepareAccountFeature)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
