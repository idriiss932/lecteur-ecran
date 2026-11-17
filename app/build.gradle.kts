plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val vc = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.example.lecteurecran"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.lecteurecran"
        minSdk = 26
        targetSdk = 34
        versionCode = vc
        versionName = "1.$vc"
    }

    // Clé de signature fixe : indispensable pour que les mises à jour s'installent par-dessus l'appli
    signingConfigs {
        getByName("debug") {
            storeFile = file("lecteur.jks")
            storePassword = "lecteur123"
            keyAlias = "lecteur"
            keyPassword = "lecteur123"
        }
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
    implementation("androidx.core:core:1.13.1")
    // OCR hors ligne (modèle latin embarqué : français, anglais, espagnol, allemand, italien...)
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
