plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.assetcapturerig"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.assetcapturerig"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0-ai"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Inject repository secret from GitHub Actions environment
        val apiKey = System.getenv("DASHSCOPE_API_KEY") ?: ""
        buildConfigField("String", "DASHSCOPE_API_KEY", "\"$apiKey\"")
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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // Sceneview ARCore framework
    implementation("io.github.sceneview:arsceneview:0.10.2")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
