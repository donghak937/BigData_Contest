plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// Optional Kakao Local REST key, read from the git-ignored local.properties (KAKAO_REST_KEY=...).
val kakaoRestKey: String = rootProject.file("local.properties").takeIf { it.isFile }
    ?.readLines()?.firstOrNull { it.trim().startsWith("KAKAO_REST_KEY=") }
    ?.substringAfter("=")?.trim().orEmpty()

android {
    namespace = "kr.heureum.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "kr.heureum.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 7
        versionName = "0.7.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "KAKAO_REST_KEY", "\"$kakaoRestKey\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
}

dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
