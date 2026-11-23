plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------- the .env gate
//
// The APK may carry exactly two values, and they are named here one by one,
// deliberately. A loop over .env would ship whatever .env happens to hold —
// and .env holds the service-role key, a Supabase personal access token and
// the Gemini key. The whole security story of this device is that losing it
// costs nothing, which is true only while none of those are inside it.

val SAFE_KEYS = setOf("SUPABASE_URL", "SUPABASE_PUBLISHABLE_KEY")

fun dotenv(name: String): String {
    require(name in SAFE_KEYS) {
        "$name is not one of the values the APK is allowed to carry"
    }
    val file = rootProject.file(".env")
    require(file.exists()) {
        "no .env at ${file.absolutePath}; the app needs SUPABASE_URL and " +
            "SUPABASE_PUBLISHABLE_KEY to know which project it belongs to"
    }
    val line = Regex("""^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$""")
    for (raw in file.readLines()) {
        val m = line.find(raw) ?: continue
        val (key, value) = m.destructured
        if (key == name) return value.trim('"', '\'')
    }
    throw GradleException("$name is not set in ${file.absolutePath}")
}

// ------------------------------------------------------- the wake-word gate
//
// The Vosk model is 68MB, so it is fetched by scripts/fetch-vosk-model.sh and
// git-ignored rather than committed. That makes it possible to build a
// perfectly valid APK with no wake word in it — a clean checkout, CI, or
// anyone who has run `git clean` — and the only symptom would be that the
// headline feature silently does nothing, on a device that can only be fixed
// by shipping it another APK.
//
// So the build says so instead. Checked against the acoustic model itself
// rather than the directory, because an interrupted download leaves the
// directory there and empty.
val VOSK_MODEL_MARKER = "src/main/assets/vosk-model-en-us/am/final.mdl"

tasks.named("preBuild") {
    doFirst {
        val marker = project.file(VOSK_MODEL_MARKER)
        if (!marker.isFile) {
            throw GradleException(
                "the wake-word model is missing (no $VOSK_MODEL_MARKER).\n" +
                    "Run scripts/fetch-vosk-model.sh - without it the app builds " +
                    "fine and never answers to its name.",
            )
        }
    }
}

android {
    namespace = "dev.toffee.companion"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.toffee.companion"
        // The tablet is a Galaxy Tab A SM-T585 on Android 8.1.0, API 27.
        minSdk = 27
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 32-bit userspace: the device reports armeabi-v7a, armeabi and no
        // arm64. Anything shipping arm64-only native code will not run.
        ndk { abiFilters += "armeabi-v7a" }

        buildConfigField("String", "SUPABASE_URL", "\"${dotenv("SUPABASE_URL")}\"")
        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            "\"${dotenv("SUPABASE_PUBLISHABLE_KEY")}\"",
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    // Bundled, not the Play Services variant: the tablet has no
    // Google account and must not need one to see that he is there.
    implementation(libs.mlkit.face.detection)
    // The wake word. Offline, Apache-2.0, and -- the reason it is here
    // rather than Picovoice -- no account and no key that a vendor can
    // switch off, which is what happened to Porcupine's free tier.
    implementation(libs.vosk.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)

    // Device-side verification. The face can be checked with a
    // screenshot; whether TLS, DNS and the Keystore work on a 2016
    // tablet cannot.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
