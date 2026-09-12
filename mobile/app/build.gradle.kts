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

android.sourceSets["main"].res.srcDir(heartbeatIconResDir)
tasks.named("preBuild").configure { dependsOn(prepareHeartbeatIcon) }

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
