package vn.ybfit.sobengoan

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.google.firebase.database.DataSnapshot
import java.io.ByteArrayOutputStream

/**
 * Chạy nền suốt (dịch vụ Trợ năng) trên máy của bé:
 *  - biết app nào đang mở (chỉ đọc tên gói, KHÔNG đọc nội dung màn hình);
 *  - về màn hình chính khi app bị chặn, hết giờ riêng của app, hoặc hết giờ cả ngày;
 *  - mỗi phút gửi tín hiệu online + giờ dùng cho ba mẹ, và kiểm tra lại giới hạn
 *    (bé đang dùng dở một app mà hết giờ thì cũng bị đưa về màn hình chính);
 *  - nhận lệnh của ba mẹ: chụp màn hình, thưởng thêm giờ, tin nhắn mới.
 */
class MonitorService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = Listeners()
    private var listeningKey: String? = null

    private var rules = Rules()
    private var usage = UsageTracker.Today(emptyMap(), 0)
    private var usageAt = 0L
    private var foregroundPkg: String? = null
    private var reportedPkg: String? = null
    private var exempt: Set<String> = emptySet()
    private val lastNoticeAt = HashMap<String, Long>()

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        exempt = exemptPackages()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        listeners.clear()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == "com.android.systemui") return // thanh thông báo, hộp thoại hệ thống
        foregroundPkg = pkg
        if (!Prefs.isApproved(this)) return
        listenIfNeeded()
        refreshUsage(maxAgeMs = 15_000)
        enforce(pkg)
        if (pkg != reportedPkg) {
            reportedPkg = pkg
            Reporter.state(this, null, pkg, accessibilityOn = true)
        }
    }

    // ---------- Mỗi phút ----------

    private fun onTick() {
        if (!Prefs.isApproved(this)) {
            listeners.clear()
            listeningKey = null
            return
        }
        listenIfNeeded()
        refreshUsage(maxAgeMs = 0)
        val screenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val current = if (screenOn) foregroundPkg else null
        Reporter.state(this, usage, current, accessibilityOn = true)
        if (current != null) enforce(current)
        if (rules.locationEnabled) Reporter.locationIfDue(this)
        warnNearLimit()
    }

    private fun refreshUsage(maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        if (now - usageAt < maxAgeMs) return
        usage = UsageTracker.today(this)
        usageAt = now
    }

    // ---------- Giới hạn ----------

    private fun enforce(pkg: String) {
        if (pkg in exempt) return
        val appMinutes = usage.minutesByPkg[pkg] ?: 0
        val appLimit = rules.appLimits[pkg]
        val reason = when {
            pkg in rules.blocked -> "BLOCKED_APP"
            appLimit != null && appMinutes >= appLimit -> "APP_LIMIT"
            usage.totalMinutes >= rules.effectiveLimit -> "DAILY_LIMIT"
            else -> return
        }
        performGlobalAction(GLOBAL_ACTION_HOME)

        // Báo bé và ba mẹ, nhưng không quá 1 lần / 3 phút cho mỗi lý do + app.
        val key = "$reason/$pkg"
        val now = System.currentTimeMillis()
        if (now - (lastNoticeAt[key] ?: 0L) < NOTICE_COOLDOWN_MS) return
        lastNoticeAt[key] = now
        val name = DeviceChecks.appLabel(this, pkg) ?: pkg
        Notifier.blocked(
            this,
            when (reason) {
                "BLOCKED_APP" -> "$name đang bị ba mẹ chặn."
                "APP_LIMIT" -> "Hôm nay con đã dùng hết giờ cho $name rồi."
                else -> "Con đã dùng hết giờ dùng máy hôm nay. Mở Sổ Bé Ngoan để xin thêm giờ nhé!"
            },
        )
        Reporter.alert(this, reason, pkg)
    }

    private fun warnNearLimit() {
        val remaining = rules.effectiveLimit - usage.totalMinutes
        if (remaining in 1..WARN_BEFORE_MINUTES &&
            Prefs.markOncePerDay(this, "near_limit_${rules.effectiveLimit}", Cloud.todayKey())
        ) {
            Notifier.warning(this, "Con còn khoảng $remaining phút dùng máy hôm nay.")
        }
    }

    /**
     * Không bao giờ chặn: chính app này (để bé xin thêm giờ, nhắn ba mẹ), màn hình
     * chính, bàn phím, app gọi điện (gọi khẩn cấp), và hộp thoại cấp quyền.
     */
    private fun exemptPackages(): Set<String> {
        val dialers = packageManager.queryIntentActivities(Intent(Intent.ACTION_DIAL), 0)
            .mapNotNull { it.activityInfo?.packageName }
        return UsageTracker.ignoredPackages(this) + dialers + setOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.emergency",
            "com.android.phone",
            "com.android.server.telecom",
        )
    }

    // ---------- Lệnh từ ba mẹ ----------

    private fun listenIfNeeded() {
        val familyId = Prefs.familyId(this) ?: return
        val childId = Prefs.childId(this) ?: return
        val uid = Cloud.uid ?: return
        val key = "$familyId/$childId/$uid"
        if (key == listeningKey) return
        listeners.clear()
        listeningKey = key
        val fam = Cloud.family(familyId)
        // Lần đầu chạy: tin nhắn cũ coi như đã đọc, không báo dồn dập.
        if (Prefs.lastParentMsgAt(this) == 0L) Prefs.setLastParentMsgAt(this, System.currentTimeMillis())

        // Ba mẹ xoá máy hoặc chuyển máy sang bé khác.
        listeners.on(fam.child("devices/$uid")) { s ->
            val newChild = s.child("childId").getValue(String::class.java)
            when {
                newChild == null -> onRemovedFromFamily()
                newChild != childId -> { Prefs.setApproved(this, newChild); listenIfNeeded() }
            }
        }
        listeners.on(fam.child("deviceRules/$uid")) { s -> rules = Rules.from(s) }
        listeners.on(fam.child("commands/$uid")) { s -> onCommands(s) }
        listeners.on(fam.child("messages/$childId").orderByKey().limitToLast(1)) { s ->
            val m = s.children.lastOrNull() ?: return@on
            val at = m.child("at").getValue(Long::class.java) ?: return@on
            val text = m.child("text").getValue(String::class.java) ?: return@on
            if (m.child("from").getValue(String::class.java) == "parent" && at > Prefs.lastParentMsgAt(this)) {
                Prefs.setLastParentMsgAt(this, at)
                Notifier.message(this, text)
            }
        }
    }

    private fun onCommands(s: DataSnapshot) {
        val shotAt = s.child("screenshotAt").getValue(Long::class.java) ?: 0L
        if (shotAt > Prefs.lastScreenshotAt(this)) {
            Prefs.setLastScreenshotAt(this, shotAt)
            takeAndUploadScreenshot()
        }
        val bonusAt = s.child("bonus/at").getValue(Long::class.java) ?: 0L
        if (bonusAt > Prefs.lastBonusAt(this)) {
            Prefs.setLastBonusAt(this, bonusAt)
            val minutes = s.child("bonus/minutes").getValue(Int::class.java) ?: 0
            if (minutes > 0) Notifier.bonus(this, minutes)
        }
    }

    private fun onRemovedFromFamily() {
        // Không còn được quản lý: bỏ mọi giới hạn, quay về màn hình ghép nối.
        Prefs.clearPairing(this)
        listeners.clear()
        listeningKey = null
        rules = Rules(dailyLimit = Int.MAX_VALUE / 2)
    }

    private fun takeAndUploadScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return // cần Android 11+
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    if (bmp == null) return
                    // 960px, JPEG 65: đủ nét đọc chữ, đủ nhẹ (~100KB) cho Firebase.
                    val width = minOf(960, bmp.width)
                    val scaled = Bitmap.createScaledBitmap(bmp, width, bmp.height * width / bmp.width, true)
                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 65, out)
                    Reporter.screenshot(this@MonitorService, Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                } catch (e: Exception) {
                    // Ba mẹ có thể bấm chụp lại.
                }
            }

            override fun onFailure(errorCode: Int) {}
        })
    }

    companion object {
        private const val TICK_MS = 60_000L
        private const val NOTICE_COOLDOWN_MS = 3 * 60_000L
        private const val WARN_BEFORE_MINUTES = 5
    }
}
