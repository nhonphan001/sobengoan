package vn.ybfit.sobengoan

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager
import java.util.Calendar

/**
 * Đếm thời gian dùng từng app từ nửa đêm tới giờ, bằng sự kiện mở/đóng màn hình
 * app (UsageEvents) — chính xác hơn số tổng theo ngày của queryUsageStats.
 * Cần quyền "Truy cập dữ liệu sử dụng"; thiếu quyền thì Android trả về rỗng.
 */
object UsageTracker {
    // Hằng số của UsageEvents.Event (ACTIVITY_RESUMED/PAUSED từ API 29, cùng giá trị
    // với MOVE_TO_FOREGROUND/BACKGROUND cũ).
    private const val RESUMED = 1
    private const val PAUSED = 2

    data class Today(val minutesByPkg: Map<String, Int>, val totalMinutes: Int)

    fun today(c: Context): Today {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val now = System.currentTimeMillis()
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return Today(emptyMap(), 0)
        val events = try { usm.queryEvents(start, now) } catch (e: SecurityException) { null }
            ?: return Today(emptyMap(), 0)

        val ignored = ignoredPackages(c)
        val openSince = HashMap<String, Long>() // "gói/activity" -> lúc mở
        val seen = HashSet<String>()
        val millis = HashMap<String, Long>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            if (pkg in ignored) continue
            val key = "$pkg/${e.className}"
            when (e.eventType) {
                RESUMED -> { openSince[key] = e.timeStamp; seen += key }
                PAUSED -> {
                    // Không thấy lúc mở: app đã mở từ trước nửa đêm.
                    val from = openSince.remove(key) ?: if (key in seen) null else start
                    seen += key
                    if (from != null) millis[pkg] = (millis[pkg] ?: 0L) + (e.timeStamp - from)
                }
            }
        }
        for ((key, from) in openSince) { // đang mở tới bây giờ
            val pkg = key.substringBefore('/')
            millis[pkg] = (millis[pkg] ?: 0L) + (now - from)
        }
        val minutes = millis.mapValues { (it.value / 60_000L).toInt() }.filterValues { it > 0 }
        return Today(minutes, (millis.values.sum() / 60_000L).toInt())
    }

    /** Không tính giờ cho chính app này, màn hình chính và bàn phím. */
    fun ignoredPackages(c: Context): Set<String> {
        val pm = c.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launchers = pm.queryIntentActivities(home, 0).mapNotNull { it.activityInfo?.packageName }
        val imes = c.getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList?.map { it.packageName }.orEmpty()
        return (launchers + imes + listOf(c.packageName, "com.android.systemui", "android")).toSet()
    }
}
