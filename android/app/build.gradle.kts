plugins {
    id("com.android.application")
}

fun firebaseProp(name: String): String =
    providers.gradleProperty("sobengoan.firebase.$name").get()

android {
    namespace = "vn.ybfit.sobengoan"
    compileSdk = 36

    defaultConfig {
        applicationId = "vn.ybfit.sobengoan"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "2.0.0"

        val projectId = firebaseProp("projectId")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"$projectId\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${firebaseProp("apiKey")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${firebaseProp("appId")}\"")
        buildConfigField("String", "FIREBASE_DATABASE_URL", "\"${firebaseProp("databaseUrl")}\"")
        // Project "demo-..." chỉ tồn tại trên Firebase emulator.
        buildConfigField("boolean", "USE_EMULATOR", projectId.startsWith("demo-").toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
