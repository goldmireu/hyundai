// 안드로이드·Pleos SDK에 의존하지 않는 핵심 로직. PC에서 바로 테스트할 수 있다: ./gradlew :core:test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

// JDK 17 툴체인을 따로 받지 않고, 지금 쓰는 JDK(17 이상)로 17 바이트코드를 만든다.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
