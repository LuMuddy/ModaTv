import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 敏感值只存在于 local.properties（已被 .gitignore 排除），构建期注入 BuildConfig。
// 键名与含义见仓库里的 local.properties.example。
val localSecrets = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

fun secret(key: String): String = localSecrets.getProperty(key).orEmpty()

fun literal(raw: String): String =
    "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.moda.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.moda.tv"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "API_BASE", literal(secret("modatv.apiBase")))
        buildConfigField("String", "DEVICE_TOKEN", literal(secret("modatv.deviceToken")))
        buildConfigField("String", "DEVICE_FP", literal(secret("modatv.deviceFp")))
        buildConfigField("String", "HOT_WORDS_APP_ID", literal(secret("modatv.hotWordsAppId")))
        buildConfigField("String", "HOT_WORDS_APP_KEY", literal(secret("modatv.hotWordsAppKey")))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        // 设置页要在运行时显示版本号，必须生成 BuildConfig
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.core:core-ktx:1.13.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    // 仅核心库：只用来生成二维码矩阵，不引 zxing-android-embedded（后者体积大且带 Activity）
    implementation("com.google.zxing:core:3.5.3")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
}
