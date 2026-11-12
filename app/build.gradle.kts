plugins {
    id("com.android.application")
}

android {
    namespace = "com.harmony.batteryfix"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.harmony.batteryfix"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    // 离线构建：把 XposedBridgeAPI-82.jar 放进 app/libs，注释上一行、启用下一行
    // compileOnly(files("libs/XposedBridgeAPI-82.jar"))
}
