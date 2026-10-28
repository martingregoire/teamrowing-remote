plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Spotify client ID is kept out of the repository, in ~/.gradle/gradle.properties.
val spotifyClientId = providers.gradleProperty("teamrowingRemote.spotifyClientId").orNull
    ?: error("Missing teamrowingRemote.spotifyClientId in ~/.gradle/gradle.properties")

// The release signing key is kept out of the repository too; without it, release builds are unsigned.
// It's shared with the display app, whose properties are used.
val releaseKeystore = providers.gradleProperty("teamrowingDisplay.releaseKeystore").orNull
    ?.replaceFirst("~", System.getProperty("user.home"))

// The version comes from the latest release tag (v1.2.3), see "Releasing" in the README. Builds after
// the tag get the number of commits since then and the commit as suffix, e.g. 1.2.3-4-gabc1234.
val gitVersion = providers.exec {
    commandLine("git", "describe", "--tags", "--match", "v[0-9]*", "--dirty")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().removePrefix("v").ifEmpty { "0.0.0" }
val (versionMajor, versionMinor, versionPatch) =
    gitVersion.substringBefore("-").split(".").map(String::toInt)

android {
    namespace = "de.gregoire.teamrowingremote"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "de.gregoire.teamrowingremote"
        minSdk = 26
        targetSdk = 37
        // Minor and patch stay below 100, so the version code grows with every release
        versionCode = versionMajor * 10000 + versionMinor * 100 + versionPatch
        versionName = gitVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"teamrowing-remote://callback\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.gradleProperty("teamrowingDisplay.releaseKeystorePassword").get()
                keyAlias = providers.gradleProperty("teamrowingDisplay.releaseKeyAlias").get()
                // PKCS12 keystores, keytool's default, use the same password for the key
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // Optimizes the whole app; limiting it to the libraries with packageScope made R8 produce
            // classes that fail to load in the display app (IllegalAccessError in kotlin.collections)
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    // Spotify App Remote SDK, only distributed as AAR: https://github.com/spotify/android-sdk/releases
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation(libs.gson)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
