plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.galleryai1"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.example.galleryai1"
        // Room/ONNX Runtime Mobile은 더 낮은 API도 지원하지만, 프로젝트 초기부터 26으로 맞춰왔고
        // 낮출 이유가 없어 그대로 유지한다.
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // translate/onnxruntime-android 네이티브 라이브러리가 기본적으로 4개 아키텍처(arm64-v8a,
        // armeabi-v7a, x86, x86_64)를 전부 APK에 담아서 용량이 크게 늘어난다. 실제로 쓰는 건
        // 실기기(arm64-v8a, 요즘 안드로이드 기기 표준)와 로컬 에뮬레이터(x86_64)뿐이라 나머지
        // 두 32비트 아키텍처(요즘 기기엔 없음)는 빼서 APK 용량을 줄인다.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("com.github.bumptech.glide:glide:4.16.0")
    // XML 레이아웃 필수 구성 요소 추가
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("com.github.chrisbanes:PhotoView:2.3.0")
    // AI 태깅/검색어-사진 매칭을 전부 담당하는 비전-언어 모델(MobileCLIP, ONNX). AutoTagger/
    // PreciseTagger/AiCaptioner/AiPromptTagger/FaceTagger 4~6단 ML Kit 폴백 체인을 이걸로 대체했다
    // — MobileClipEngine.kt, assets/mobileclip/ 참고. 완전 로컬 추론(Gemini Nano AICore처럼
    // 기기별 서버 승인에 의존하지 않는다), CPU 실행.
    implementation(libs.onnxruntime.android)
    // 검색어(한국어) <-> TagMaster 영문 태그명 매칭 및 사용자 정의 태그의 영어 임베딩 계산용 번역.
    // AICore 불필요, 모든 안드로이드 기기에서 동작하는 정식 출시된 안정 버전(TranslationTagger.kt).
    implementation("com.google.mlkit:translate:17.0.3")
    // setting.md 기획서의 Room 3테이블 구조(PhotoAlbum/TagMaster/PhotoTagMap) — 기존 LabelStore의
    // SharedPreferences JSON 저장 방식을 대체한다(AppDatabase.kt).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.viewpager2)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
