import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.saider.turmalin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.saider.turmalin"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Reconocimiento de manuscrita on-device (RF-24, RNF-04): Digital Ink lee
    // los puntos de los trazos, no imágenes. El modelo de español se descarga
    // UNA vez vía Play Services (excepción aprobada a "cero red", ver
    // CLAUDE.md); el reconocimiento nunca sale del dispositivo.
    implementation(libs.mlkit.digital.ink.recognition)

    // Ink API de Google: captura, renderizado de baja latencia, brushes,
    // geometría y almacenamiento de trazos. Fase 0 solo usa authoring + rendering,
    // pero el resto de módulos queda declarado para las fases siguientes.
    implementation(libs.androidx.ink.nativeloader)
    implementation(libs.androidx.ink.rendering)
    implementation(libs.androidx.ink.strokes)
    implementation(libs.androidx.ink.authoring.compose)
    implementation(libs.androidx.ink.brush.compose)
    implementation(libs.androidx.ink.geometry.compose)
    implementation(libs.androidx.ink.storage)

    // Predictor de movimiento: parte de la receta oficial de baja latencia de Ink API.
    implementation(libs.androidx.input.motionprediction)

    testImplementation(libs.junit)
}
