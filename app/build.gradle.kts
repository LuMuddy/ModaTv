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

        /**
         * 只保留 TV 盒子真正常见的两个 ABI。
         * 原因是 ijk 的 native 包同时打了 arm64-v8a / armeabi-v7a / armeabi / x86_64，
         * 每个 ABI 的 libijkffmpeg.so 都在 5~12MB，全量收进来会让 APK 直接多出几十 MB，
         * 而电视端几乎不存在 x86/armeabi 设备，所以按 ABI 过滤是性价比最高的一刀。
         */
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
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

    /**
     * IJK 内核：只用一个自包含的 AAR，它同时带 tv.danmaku.ijk.media.player.IjkMediaPlayer 的
     * Java 类和 arm64-v8a / armeabi-v7a 的 libijkplayer/libijkffmpeg/libijksdl.so。
     *
     * 为什么用这个坐标而不是官方 tv.danmaku.ijk.media:ijkplayer-java:0.8.8：
     * 官方 0.8.8 只发布在 jcenter 上，jcenter 已停服；实测 Maven Central 与 jitpack 上都没有
     * tv.danmaku.ijk.media 这个 group（POM 全部 404），而本项目 settings.gradle.kts 里
     * repositoriesMode 是 FAIL_ON_PROJECT_REPOS，app 模块不能自己加仓库，所以 0.8.8 拿不到。
     * 这个包是 IJK 0.8.8 的社区重新打包版，Java 包名与 JNI 符号都和官方一致
     * （已用 strings 校验过 .so 里的 tv/danmaku/ijk/media/player/IjkMediaPlayer 符号），
     * 且从 mavenCentral() 直接可解析，不需要额外仓库。
     */
    implementation("xyz.doikki.android.dkplayer:player-ijk:3.3.7")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    // 仅核心库：只用来生成二维码矩阵，不引 zxing-android-embedded（后者体积大且带 Activity）
    implementation("com.google.zxing:core:3.5.3")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
}
