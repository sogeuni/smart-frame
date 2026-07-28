import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    check(keystorePropertiesFile.isFile) {
        "Missing signing configuration: ${keystorePropertiesFile.path}"
    }
    keystorePropertiesFile.inputStream().use(::load)
}

fun keystoreProperty(name: String): String =
    requireNotNull(keystoreProperties.getProperty(name)?.takeIf(String::isNotBlank)) {
        "Missing '$name' in ${keystorePropertiesFile.path}"
    }

android {
    namespace = "dev.sogn.smartframe"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.sogn.smartframe"
        minSdk = 27
        targetSdk {
            version = release(36)
        }
        versionCode = 9_000
        versionName = "0.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(keystoreProperty("storeFile"))
            storePassword = keystoreProperty("storePassword")
            keyAlias = keystoreProperty("keyAlias")
            keyPassword = keystoreProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.preference.ktx)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.analytics)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
