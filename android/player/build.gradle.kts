plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ender.ninniplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ender.ninniplayer"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key until a real signing key is added.
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(project(":protocol"))
    implementation("androidx.core:core-ktx:1.13.1")
    // MediaControllerCompat: shuffle/repeat of the music app
    implementation("androidx.media:media:1.7.0")
}
