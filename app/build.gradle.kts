// Plugins
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Robolectric-SDKs für Tests
val robolectricSdk35 by configurations.creating
val robolectricSdk29 by configurations.creating
val prepareRobolectricSdk by tasks.registering(Copy::class) {
    from(robolectricSdk35)
    from(robolectricSdk29)
    into(layout.buildDirectory.dir("robolectric-sdk"))
}

// Hilfsfunktionen
fun buildString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Android-Konfiguration
android {
    namespace = "de.luma.calendar"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "de.luma.calendar"
        minSdk = 29
        targetSdk = 36
        versionCode = 9
        versionName = "0.9.0-alpha"
        buildConfigField("String", "PRIVACY_POLICY_URL", buildString(providers.gradleProperty("gimbo.calendar.privacyPolicyUrl").orElse("").get()))
        buildConfigField("String", "PUBLISHER_CONTACT", buildString(providers.gradleProperty("gimbo.calendar.publisherContact").orElse("").get()))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    bundle { language { enableSplit = false } }
    sourceSets.getByName("test").resources.srcDir("schemas")
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.dependsOn(prepareRobolectricSdk)
            it.systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath)
            if (providers.gradleProperty("screenshots").isPresent) {
                it.systemProperty("screenshots.dir", rootProject.file("docs/screenshots").absolutePath)
                it.forkEvery = 1
            } else it.exclude("**/StoreScreenshots*")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs.getByName("debug") {
        providers.environmentVariable("ANDROID_USER_HOME").orNull?.let { storeFile = file("$it/debug.keystore") }
    }
    val uploadFile = providers.environmentVariable("GIMBO_CALENDAR_UPLOAD_STORE_FILE").orNull
    val uploadPassword = providers.environmentVariable("GIMBO_CALENDAR_UPLOAD_STORE_PASSWORD").orNull
    val uploadAlias = providers.environmentVariable("GIMBO_CALENDAR_UPLOAD_KEY_ALIAS").orNull
    val uploadKeyPassword = providers.environmentVariable("GIMBO_CALENDAR_UPLOAD_KEY_PASSWORD").orNull
    if (listOf(uploadFile, uploadPassword, uploadAlias, uploadKeyPassword).all { !it.isNullOrBlank() }) {
        signingConfigs.create("upload") { storeFile = file(uploadFile!!); storePassword = uploadPassword; keyAlias = uploadAlias; keyPassword = uploadKeyPassword }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("upload")
    }
    buildTypes { release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) } }
}

// Kotlin und Room
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// Abhängigkeiten
dependencies {
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    robolectricSdk35("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
    robolectricSdk29("org.robolectric:android-all-instrumented:10-robolectric-5803371-i7")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
