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
        // Giữ đúng một đối tượng Firebase cho cả app: FirebaseDatabase.getInstance()
        // có lúc trả về đối tượng mới chưa trỏ tới emulator, làm dữ liệu đi nhầm chỗ.
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()
        if (BuildConfig.USE_EMULATOR) {
            // 10.0.2.2 là địa chỉ của máy tính khi nhìn từ máy ảo Android.
            auth.useEmulator(EMULATOR_HOST, 9099)
            database.useEmulator(EMULATOR_HOST, 9000)
        }
        if (BuildConfig.DEBUG) database.setLogLevel(com.google.firebase.database.Logger.Level.DEBUG)
        Notifier.createChannels(this)
    }

    companion object {
        const val EMULATOR_HOST = "10.0.2.2"

        lateinit var auth: FirebaseAuth
            private set
        lateinit var database: FirebaseDatabase
            private set
    }
}
