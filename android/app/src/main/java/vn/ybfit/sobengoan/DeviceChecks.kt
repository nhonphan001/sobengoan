package vn.ybfit.sobengoan

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Kiểm tra các quyền app cần, và thông tin máy để báo cho ba mẹ. */
object DeviceChecks {

    /** Trợ năng (AccessibilityService) — thiếu thì không chặn, không giới hạn được gì. */
    fun accessibilityOn(c: Context): Boolean {
        val expected = ComponentName(c, MonitorService::class.java)
        val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    /** "Truy cập dữ liệu sử dụng" — thiếu thì không đếm được giờ dùng. */
    fun usageAccessOn(c: Context): Boolean {
        val appOps = c.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun notificationsOn(c: Context) = NotificationManagerCompat.from(c).areNotificationsEnabled()

    fun locationOn(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun batteryPercent(c: Context): Int? =
        c.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

    fun appLabel(c: Context, pkg: String): String? = try {
        val pm = c.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
