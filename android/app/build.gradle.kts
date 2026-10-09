import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名配置从 gitignored 的 keystore.properties 读取（不含密钥，不泄露）
val keystoreProperties = Properties().apply {
    val f = file("keystore.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.mit.reader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mit.reader"
        minSdk = 26
        targetSdk = 34
        versionCode = 27
        versionName = "1.4.27"
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.containsKey("storeFile")) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.core:core-ktx:1.13.1")

    // 图片加载：本地文件 + 网络
    implementation("io.coil-kt:coil-compose:2.7.0")
    // 网络：multipart 上传 / JSON / job 轮询
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // SAF 文件夹访问（书库文件夹自动扫描导入）
    implementation("androidx.documentfile:documentfile:1.0.1")
    // 漫画压缩包 CBR(RAR4/RAR5) 解压；CBZ(ZIP) 用 JDK 自带 java.util.zip
    // 注意：junrar 不支持 RAR5（会抛 UnsupportedRarV5Exception），所以用纯 Java 的 unrar5j
    implementation("io.github.realburst:unrar5j:v2.0.4")
    // 设备对传书：接收方起本地 HTTP 服务（NSD 发现 + 直连传输）
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
