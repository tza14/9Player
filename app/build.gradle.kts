plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "moe.tekuza.m9player"
    ndkVersion = "28.2.13676358"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "moe.tekuza.m9player"
        minSdk = 29
        targetSdk = 36
        versionCode = 54
        versionName = "1.7.7"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++23")
            }
        }

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
            // release 下 logDebug 整体不执行（消息字符串也不拼）；Log.w / Log.e 始终保留
            buildConfigField("boolean", "VERBOSE_LOGS", "false")
        }
        debug {
            buildConfigField("boolean", "VERBOSE_LOGS", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        disable += setOf(
            "DirectCalendarInstanceUsage",
            "DirectDateInstantiation",
            "DirectSystemCurrentTimeMillisUsage",
            "DuplicateCrowdInStrings",
        )
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

if (file("debug-suffix.gradle").exists()) {
    apply(from = "debug-suffix.gradle")
}

dependencies {
    implementation(files("libs/xms-wearable-lib_1.4_release.aar"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-transformer:1.3.1")
    implementation("androidx.media:media:1.7.0") {
        // PlaybackNotificationController 使用 media3 MediaSession.sessionCompatToken，
        // 其返回类型 MediaSessionCompat.Token 来自 androidx.media，media3-session 未以 api 传递。
        because("MediaSessionCompat.Token type for MediaSession.sessionCompatToken")
    }
    implementation("com.jaredrummler:colorpicker:1.1.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.github.ankidroid:Anki-Android:api-v1.1.0")
    implementation("io.github.kyant0:taglib:1.0.5")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("me.saket.telephoto:zoomable-image-coil3:0.19.0")
    testImplementation(libs.junit)
}

























