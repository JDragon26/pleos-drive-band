import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// release 서명 키: 저장소 밖. 위치는 사용자 gradle.properties 의 pleosKeystoreProps 로 준다. 없으면 서명 없이 빌드
val keyProps = Properties().apply {
    val f = providers.gradleProperty("pleosKeystoreProps").orNull?.let { file(it) }
    if (f != null && f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.team35.driveband"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.team35.driveband"
        minSdk = 28 // Pleos 요구사항 (차량 보안)
        targetSdk = 34 // Pleos Connect 에뮬레이터 = API 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keyProps.isNotEmpty()) create("release") {
            storeFile = file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    useLibrary("android.car") // 차속(PERF_VEHICLE_SPEED) — Pleos SDK 에 없는 표준 차량 API
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // 단위 테스트에서 org.json (안드로이드 스텁 대신)
}
