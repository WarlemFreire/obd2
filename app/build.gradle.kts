import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    // AGP 9 já compila Kotlin (built-in Kotlin); só o compilador do Compose entra à parte.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.soswf.obd"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.soswf.obd"
        minSdk = 26
        targetSdk = 36
        // No CI: número da execução e commit (ex.: 0.2.7-04870f5). Local: "dev".
        val execucao = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = execucao ?: 1
        versionName = "0.2." + (execucao?.toString() ?: "0") + "-" + (System.getenv("GITHUB_SHA")?.take(7) ?: "dev")
    }

    // Chave fixa no repositório: sem ela cada compilação do CI sai com uma assinatura nova e o
    // Android recusa instalar a atualização por cima. É chave de debug, para uso próprio, não de loja.
    signingConfigs {
        getByName("debug") {
            storeFile = file("assinatura-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
