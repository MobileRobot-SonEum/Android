import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 에이전트 서버 주소·키·장치 ID는 local.properties(커밋 안 됨)에서 읽어 BuildConfig로 주입.
// 항목 이름은 local.properties.example 참고. 없으면 빈 문자열 → 앱 화면에 "설정 필요" 표시.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
fun localString(key: String): String {
    val v = localProps.getProperty(key, "").trim()
    return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "com.damum.daycaretemi"
    compileSdk {
        version = release(33)
    }

    defaultConfig {
        applicationId = "com.damum.daycaretemi"
        minSdk = 23
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "AGENT_BASE_URL", localString("agent.baseUrl"))
        buildConfigField("String", "AGENT_API_KEY", localString("agent.apiKey"))
        buildConfigField("String", "TEMI_DEVICE_ID", localString("temi.deviceId"))

        // temi(RK3288)는 32비트 ARM만 지원
        ndk {
            abiFilters += "armeabi-v7a"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // MediaPipe .task 모델은 압축하지 않아야 메모리 매핑으로 읽을 수 있음
        noCompress += "task"
    }

    // temi: 실기기용(기본). emulator: temi SDK 없이 FakeRobotController + 개발 패널
    flavorDimensions += "device"
    productFlavors {
        create("temi") {
            dimension = "device"
            isDefault = true
        }
        create("emulator") {
            dimension = "device"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // MediaPipe가 java.util.Optional 등 API 24 클래스를 사용 → API 23(temi)용 desugaring
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)

    // temi SDK는 temi 빌드에만. main 코드가 Robot을 직접 부르면 emulator 빌드에서 컴파일 에러 → RobotController만 사용
    "temiImplementation"(libs.temi.sdk)

    // 지금은 미사용. 온디바이스 Pose lite·카메라 옵션용으로 유지 (Manifest overrideLibrary와 짝)
    implementation(libs.mediapipe.tasks.vision)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.bundles.camerax)

    // 노트북 스트리밍 서버(ws://)·EC2 에이전트 서버(https/wss) 통신
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
