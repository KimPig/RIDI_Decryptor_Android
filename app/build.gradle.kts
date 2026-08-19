import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val requestedReleaseDate = providers.gradleProperty("releaseDate").orNull
    ?: System.getenv("RIDI_RELEASE_DATE")
    ?: LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ISO_LOCAL_DATE)
val releaseDate = LocalDate.parse(requestedReleaseDate.replace('.', '-'))
val releaseVersionName = releaseDate.format(DateTimeFormatter.ofPattern("uuuu.MM.dd"))
val releaseVersionCode = releaseDate.format(DateTimeFormatter.BASIC_ISO_DATE).toInt()

android {
    namespace = "com.kimpig.rididecryptor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kimpig.rididecryptor"
        minSdk = 24
        targetSdk = 35
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.github.topjohnwu.libsu:io:6.0.0")
    implementation("io.realm:realm-android-library:10.19.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.register<Copy>("packageDebugRelease") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("artifacts/apk"))
    rename { "RIDI_Decryptor_Android-v$releaseVersionName-debug.apk" }
}
