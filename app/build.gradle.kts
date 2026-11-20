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

android {
    namespace = "dev.toffee.companion"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.toffee.companion"
        // The tablet is a Galaxy Tab A SM-T585 on Android 8.1.0, API 27.
        minSdk = 27
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
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
