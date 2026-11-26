plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.10"
}
val samsungAar = providers.gradleProperty("samsungHealthAar").orNull?.let { file(it) }
if (samsungAar != null) require(samsungAar.isFile) { "samsungHealthAar must name the licensed Samsung Health Data SDK AAR" }

val samsungSigner = providers.gradleProperty("samsungSigningSha256").orNull.orEmpty().replace(":", "").lowercase()
require(samsungSigner.isEmpty() || samsungSigner.matches(Regex("[0-9a-f]{64}"))) { "Invalid Samsung app signing SHA-256 fingerprint" }

android {
    buildFeatures { buildConfig = true }
    sourceSets["main"].java.srcDir(if (samsungAar == null) "src/standard/java" else "src/samsung/java")
    namespace = "dev.lvtd.healthledger"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.lvtd.healthledger"
        buildConfigField("String", "SAMSUNG_SIGNER", "\"$samsungSigner\"")
        minSdk = 34
        targetSdk = 36
        versionCode = 3
        versionName = "0.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Device tests have a separate sandbox, so they cannot use the phone owner's account or permissions.
    buildTypes {
        create("probe") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".probe"
            matchingFallbacks += "debug"
        }
    }
    testBuildType = "probe"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    if (samsungAar != null) {
        implementation(files(samsungAar))
        implementation("org.jetbrains.kotlin:kotlin-parcelize-runtime:2.2.10")
        implementation("com.google.code.gson:gson:2.9.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    }
    implementation("org.jsoup:jsoup:1.24.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
