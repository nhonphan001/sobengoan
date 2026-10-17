package vn.ybfit.sobengoan

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
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
    private const val SCREEN_NON_INTERACTIVE = 16
    private const val KEYGUARD_SHOWN = 17
    private const val DEVICE_SHUTDOWN = 26

    data class Today(val minutesByPkg: Map<String, Int>, val totalMinutes: Int)

    /**
     * Đi theo dòng thời gian: mỗi lúc chỉ MỘT màn hình đứng trước, thời gian tính cho
     * app của màn hình đó tới khi màn hình khác mở lên, nó đóng lại, hoặc tắt/khoá
     * màn hình. Cách cũ cộng từng màn hình riêng lẻ, nên khi máy (nhất là máy tính
     * bảng nhiều cửa sổ) không ghi lúc đóng một màn hình thì giờ bị tính dư rất nhiều.
     */
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
        val millis = HashMap<String, Long>()
        var curKey: String? = null // "gói/activity" đang đứng trước
        var since = start
        var sawAny = false

        fun close(at: Long) {
            val key = curKey ?: return
            val pkg = key.substringBefore('/')
            if (pkg !in ignored) millis[pkg] = (millis[pkg] ?: 0L) + (at - since).coerceAtLeast(0)
            curKey = null
        }

        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val ts = e.timeStamp
            when (e.eventType) {
                RESUMED -> {
                    close(ts)
                    curKey = "${e.packageName}/${e.className}"
                    since = ts
                    sawAny = true
                }
                PAUSED -> {
                    val key = "${e.packageName}/${e.className}"
                    if (key == curKey) close(ts)
                    // Màn hình mở từ trước nửa đêm, chưa thấy lúc mở: tính từ nửa đêm.
                    else if (!sawAny && curKey == null) { curKey = key; since = start; close(ts) }
                    sawAny = true
                }
                SCREEN_NON_INTERACTIVE, KEYGUARD_SHOWN, DEVICE_SHUTDOWN -> close(ts)
            }
        }
        val screenOn = c.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        if (screenOn) close(now) // app đang dùng tới bây giờ
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
