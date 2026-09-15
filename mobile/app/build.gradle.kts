plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.heartbeatheaven.app"
    compileSdk = 35
    defaultConfig { applicationId = "com.heartbeatheaven.app"; minSdk = 23; targetSdk = 35; versionCode = 2; versionName = "1.0.1" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("RELEASE_STORE_FILE")
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
                storeType = System.getenv("RELEASE_STORE_TYPE") ?: "PKCS12"
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

val heartbeatIconResDir = layout.buildDirectory.dir("generated/res/heartbeatIcon")
val prepareHeartbeatIcon by tasks.registering {
    outputs.dir(heartbeatIconResDir)
    doLast {
        val source = rootProject.projectDir.parentFile.resolve("public/heartbeat-heaven-logo.png")
        val target = heartbeatIconResDir.get().dir("drawable").file("heartbeat_heaven_logo.png").asFile
        check(source.exists()) { "HEARTBEAT HEAVEN logo not found at ${source.absolutePath}" }
        target.parentFile.mkdirs(); source.copyTo(target, overwrite = true)
    }
}

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

val prepareAccountFeature by tasks.registering {
    doLast {
        val source = rootProject.projectDir.resolve("app/src/main/java/com/heartbeatheaven/app/MainActivity.kt")
        var text = source.readText()
        if (!text.contains("NavigationBarItem(tab == 3")) {
            val marker = "NavigationBarItem(tab == 2, { tab = 2; selected = null }, { Icon(Icons.Default.Favorite, \"Favorites\") }, label = { Text(\"Favorites\") })"
            if (marker in text) text = text.replace(marker, marker + "\n                    NavigationBarItem(tab == 3, { tab = 3; selected = null }, { Icon(Icons.Default.Person, \"Profile\") }, label = { Text(\"Profile\") })")
        }
        source.writeText(text)
    }
}

val prepareFriendsFeature by tasks.registering {
    doLast {
        val source = rootProject.projectDir.resolve("app/src/main/java/com/heartbeatheaven/app/MainActivity.kt")
        var text = source.readText()
        val searchNav = "NavigationBarItem(tab == 1, { tab = 1; selected = null }, { Icon(Icons.Default.Search, \"Search\") }, label = { Text(\"Search\") })"
        if (!text.contains("FriendsScreen()") && searchNav in text) {
            text = text.replace(searchNav, searchNav + "\n                    NavigationBarItem(tab == 2, { tab = 2; selected = null }, { Icon(Icons.Default.People, \"Friends\") }, label = { Text(\"Friends\") })")
            text = text.replace("NavigationBarItem(tab == 2, { tab = 2; selected = null }, { Icon(Icons.Default.Favorite, \"Favorites\") }, label = { Text(\"Favorites\") })", "NavigationBarItem(tab == 3, { tab = 3; selected = null }, { Icon(Icons.Default.Favorite, \"Favorites\") }, label = { Text(\"Favorites\") })")
            text = text.replace("NavigationBarItem(tab == 3, { tab = 3; selected = null }, { Icon(Icons.Default.Person, \"Profile\") }, label = { Text(\"Profile\") })", "NavigationBarItem(tab == 4, { tab = 4; selected = null }, { Icon(Icons.Default.Person, \"Profile\") }, label = { Text(\"Profile\") })")
            text = text.replace("when {\n                tab == 3 -> AccountScreen()", "when {\n                tab == 4 -> AccountScreen()\n                tab == 2 -> FriendsScreen()")
        }
        source.writeText(text)
    }
}

val prepareRealtimeChatFix by tasks.registering {
    doLast {
        val source = rootProject.projectDir.resolve("app/src/main/java/com/heartbeatheaven/app/FriendsScreen.kt")
        var text = source.readText()
        text = text.replace("private class FriendsApi(private val session: AuthSession)", "private class FriendsApi(internal val session: AuthSession)")
        source.writeText(text)
    }
}

val prepareChatV2TimestampFix by tasks.registering {
    doLast {
        val source = rootProject.projectDir.resolve("app/src/main/java/com/heartbeatheaven/app/FriendsScreen.kt")
        var text = source.readText()
        text = text.replace("m.createdAt.takeLast(14).take(5)", "ChatTimeFormatter.time(m.createdAt)")
        source.writeText(text)
    }
}

val prepareChatV2DeliveryFix by tasks.registering {
    doLast {
        val source = rootProject.projectDir.resolve("app/src/main/java/com/heartbeatheaven/app/FriendsScreen.kt")
        var text = source.readText()

        // Seen is only valid after delivery. This prevents an impossible Seen state
        // when the recipient has never received the message.
        text = text.replace(
            "request(\"/rest/v1/messages?sender_id=eq.$other&receiver_id=eq.${userId()}&read_at=is.null\", \"PATCH\", JSONObject().put(\"read_at\", Instant.now().toString()).toString())",
            "request(\"/rest/v1/messages?sender_id=eq.$other&receiver_id=eq.${userId()}&read_at=is.null&delivered_at=not.is.null\", \"PATCH\", JSONObject().put(\"read_at\", Instant.now().toString()).toString())"
        )

        // Deliver first, then mark Seen. Never mark Seen before a delivery exists.
        text = text.replace(
            "            runCatching {\n                a.markSeen(current.id)\n                val fresh = a.messages(current.id)\n                fresh.filter { it.senderId == current.id && it.deliveredAt.isBlank() }.forEach { a.markDelivered(it.id) }",
            "            runCatching {\n                val fresh = a.messages(current.id)\n                fresh.filter { it.senderId == current.id && it.deliveredAt.isBlank() }.forEach { a.markDelivered(it.id) }\n                a.markSeen(current.id)"
        )

        // A realtime message is initially only received locally. The server confirms
        // delivery/Seen, then the normal polling refreshes the authoritative state.
        text = text.replace(
            "messages = messages + ChatMessage(id, senderId, body, createdAt, Instant.now().toString(), \"\")",
            "messages = messages + ChatMessage(id, senderId, body, createdAt, \"\", \"\")"
        )

        // A stale read_at value without delivered_at must never render as Seen.
        text = text.replace(
            "m.readAt.isNotBlank() -> \"✓✓ Seen\"",
            "m.readAt.isNotBlank() && m.deliveredAt.isNotBlank() -> \"✓✓ Seen\""
        )
        text = text.replace(
            "color = if (mine && m.readAt.isNotBlank()) MaterialTheme.colorScheme.primary",
            "color = if (mine && m.readAt.isNotBlank() && m.deliveredAt.isNotBlank()) MaterialTheme.colorScheme.primary"
        )

        source.writeText(text)
    }
}

android.sourceSets["main"].res.srcDir(heartbeatIconResDir)
tasks.named("preBuild").configure { dependsOn(prepareHeartbeatIcon); dependsOn(preparePlayerProgressFix); dependsOn(prepareAccountFeature); dependsOn(prepareFriendsFeature); dependsOn(prepareRealtimeChatFix); dependsOn(prepareChatV2TimestampFix); dependsOn(prepareChatV2DeliveryFix) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom); androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
