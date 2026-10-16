package vn.ybfit.sobengoan

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // Khởi tạo Firebase bằng cấu hình trong gradle.properties (không dùng
        // google-services.json), để đổi giữa emulator và project thật cho dễ.
        if (FirebaseApp.getApps(this).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setDatabaseUrl(BuildConfig.FIREBASE_DATABASE_URL)
                .build()
            FirebaseApp.initializeApp(this, options)
        }
        if (BuildConfig.USE_EMULATOR) {
            // 10.0.2.2 là địa chỉ của máy tính khi nhìn từ máy ảo Android.
            FirebaseAuth.getInstance().useEmulator(EMULATOR_HOST, 9099)
            FirebaseDatabase.getInstance().useEmulator(EMULATOR_HOST, 9000)
        }
        Notifier.createChannels(this)
    }

    companion object {
        const val EMULATOR_HOST = "10.0.2.2"
    }
}
