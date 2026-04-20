import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProps = Properties().also { props ->
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { props.load(it) }
}

android {
    namespace = "com.example.rarebit"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.rarebit"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GITHUB_PAT", "\"${localProps.getProperty("github.pat", "")}\"")
        buildConfigField("String", "BLE_CFG_SERVICE_UUID", "\"${localProps.getProperty("ble.cfg_service_uuid", "")}\"")
        buildConfigField("String", "BLE_CFG_CHAR_UUID",    "\"${localProps.getProperty("ble.cfg_char_uuid",     "")}\"")
        buildConfigField("String", "BLE_FW_CHAR_UUID",     "\"${localProps.getProperty("ble.fw_char_uuid",      "")}\"")
        buildConfigField("String", "BLE_SMP_SERVICE_UUID", "\"${localProps.getProperty("ble.smp_service_uuid",  "")}\"")
    }

    buildFeatures {
        buildConfig = true
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mcumgr.ble)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
