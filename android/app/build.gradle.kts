import java.util.Properties

plugins {
    id("com.android.application")
}

val useEmulator = providers.gradleProperty("sobengoan.useEmulator").orNull == "true"

// Với emulator dùng project "demo-sobengoan" (chỉ tồn tại trên Firebase emulator).
fun firebaseProp(name: String): String = if (useEmulator) {
    mapOf(
        "projectId" to "demo-sobengoan",
        "apiKey" to "demo-api-key",
        "appId" to "1:000000000000:android:0000000000000000",
        "databaseUrl" to "https://demo-sobengoan-default-rtdb.firebaseio.com",
    ).getValue(name)
} else {
    providers.gradleProperty("sobengoan.firebase.$name").get()
}

// Khoá ký bản chính thức nằm ngoài repo (mất khoá thì không cập nhật đè được app
// đã cài, phải gỡ ra và ghép nối lại). Đường dẫn đổi được bằng -Psobengoan.keystore=...
val keystoreProps = Properties().apply {
    val path = providers.gradleProperty("sobengoan.keystore").orNull
        ?: "${System.getProperty("user.home")}/.android-keys/sobengoan-keystore.properties"
    val f = file(path)
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "vn.ybfit.sobengoan"
    compileSdk = 36

    defaultConfig {
        applicationId = "vn.ybfit.sobengoan"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "2.0.2"

        val projectId = firebaseProp("projectId")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"$projectId\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${firebaseProp("apiKey")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${firebaseProp("appId")}\"")
        buildConfigField("String", "FIREBASE_DATABASE_URL", "\"${firebaseProp("databaseUrl")}\"")
        buildConfigField("boolean", "USE_EMULATOR", useEmulator.toString())
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")

    implementation("com.google.android.gms:play-services-location:21.3.0")
}
