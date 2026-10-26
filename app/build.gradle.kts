plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "ai.byoa.probe"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.byoa.probe"
        minSdk = 28          // Pleos 개발 환경 요구사항: Android API 28 이상
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-probe"
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
    implementation(project(":core"))

    // Pleos Connect SDK (2026.10.19 릴리스 노트 기준 버전)
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
}
