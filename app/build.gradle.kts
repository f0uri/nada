plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.nada.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nada.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 67
        versionName = "1.67"
        multiDexEnabled = true
    }

    signingConfigs {
        getByName("debug") {
            val stable = rootProject.file("signing/nada.jks")
            val fallback = rootProject.file("debug.keystore")
            val chosen = if (stable.exists()) stable else fallback
            if (chosen.exists()) {
                storeFile = chosen
                if (chosen.name.endsWith(".jks")) {
                    storePassword = "NadaIgSave2026x"
                    keyAlias = "nada"
                    keyPassword = "NadaIgSave2026x"
                } else {
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.okhttp)
    implementation(libs.nanohttpd)
    implementation(libs.play.services.ads)
}
