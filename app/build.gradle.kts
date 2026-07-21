plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.blyen.ytv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.blyen.ytv"
        minSdk = 23
        targetSdk = 35
        versionCode = getVersionCode()
        versionName = getVersionName()
        // 纯播放：内置源、无远程订阅/验证/更新
        buildConfigField("boolean", "PLAYBACK_ONLY", "true")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            // 新证书 ytv-release.jks；旧 yourtv-release.jks 仅备份，勿用于新包
            val ks = rootProject.file("keystore/ytv-release.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = (project.findProperty("YTV_STORE_PASSWORD") as String?) ?: "ytvrelease"
                keyAlias = (project.findProperty("YTV_KEY_ALIAS") as String?) ?: "ytv"
                keyPassword = (project.findProperty("YTV_KEY_PASSWORD") as String?) ?: "ytvrelease"
            }
        }
    }

    buildTypes {
        release {
            // minify 会导致真机黑屏（R8 过度优化 + 不完整 keep），与 debug 对齐先关闭
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "ENABLE_LOG", "false")
            buildConfigField("boolean", "PLAYBACK_ONLY", "true")
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning?.storeFile?.exists() == true) {
                signingConfig = releaseSigning
            }
        }
        debug {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "ENABLE_LOG", "true")
            buildConfigField("boolean", "PLAYBACK_ONLY", "true")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/io.netty.versions.properties"
            )
        }
    }

    applicationVariants.all {
        outputs.all {
            if (this is com.android.build.gradle.internal.api.BaseVariantOutputImpl) {
                val appName = "ytv"
                val newName = "${appName}_v${getVersionName()}.apk"
                outputFileName = newName
            }
        }
    }
}

fun getVersionName(): String {
    return "0.2"
}

fun getVersionCode(): Int {
    val parts = getVersionName().split(".")
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return major * 100 + minor * 10 + patch
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.core.ktx.v1160)
    implementation(libs.coroutines)
    implementation(libs.exoplayer)
    implementation(libs.fragment.ktx.v184)
    implementation(libs.gson)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx.v290)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.datasource.rtmp)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.exoplayer.v111)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui.v111)
    implementation(libs.okhttp)
    implementation(libs.recyclerview)
    implementation(libs.viewbinding)
    implementation(files("libs/lib-decoder-ffmpeg-release.aar"))
}