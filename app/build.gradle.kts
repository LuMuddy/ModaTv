import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名材料只存在于 local.properties（已被 .gitignore 排除），构建期读进来建 signingConfig。
// 四个键：modatv.keystoreFile / modatv.keystorePassword / modatv.keyAlias / modatv.keyPassword。
// 路径相对工程根解析，密钥文件本身也在 .gitignore 里。
val localSecrets = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

fun secret(key: String): String = localSecrets.getProperty(key).orEmpty()

android {
    namespace = "com.moda.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.moda.tv"
        minSdk = 23
        targetSdk = 34
        // 发版时由 CI 从 git tag 推导，用 -Pmodatv.versionName / -Pmodatv.versionCode 覆盖；
        // 本地不带 -P 构建就走这里的默认值，所以不必为了发一次版先改代码再提交一次。
        versionCode = (project.findProperty("modatv.versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = project.findProperty("modatv.versionName") as String? ?: "1.0.1"

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

    /**
     * 签名材料只从 local.properties 读，那文件永远不进仓库。
     * 没写 modatv.keystoreFile 时不建 signingConfig，assembleRelease 会出
     * app-release-unsigned.apk —— 装不上但编得过，本地只想看看 release 产物时不必先造密钥。
     * CI 上必须签名：workflow 用 apksigner 校验产物，签没签由构建结果说话，不靠约定。
     */
    val releaseKeystore = secret("modatv.keystoreFile")
    signingConfigs {
        if (releaseKeystore.isNotBlank()) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = secret("modatv.keystorePassword")
                keyAlias = secret("modatv.keyAlias")
                keyPassword = secret("modatv.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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

    /**
     * 微信登录二维码在本地画：服务端 wx_qr 回的是一张图片 URL，但电视上没有浏览器，
     * 也不能把二维码当成"下载一张图"——扫的必须是屏幕上的像素。所以只引 core
     * （纯 JVM 的编码器，QRCodeWriter 足够），不引 android-core / javase 那一套：
     * 那两个包带的是摄像头解码与 GUI，我们一条都不用。
     */
    implementation("com.google.zxing:core:3.5.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 请求日志用官方拦截器（BODY 级、tag = okhttp.OkHttpClient），只挂 debug 包，见 data/ApiLog.kt
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
}
