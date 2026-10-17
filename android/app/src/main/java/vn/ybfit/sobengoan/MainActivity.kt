package vn.ybfit.sobengoan

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.ServerValue
import vn.ybfit.sobengoan.databinding.ActivityMainBinding
import java.util.Calendar

/** Màn hình của bé: giờ dùng hôm nay, xin thêm giờ, điểm, lịch, nhắn tin với ba mẹ. */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val listeners = Listeners()
    private val handler = Handler(Looper.getMainLooper())
    private var rules = Rules()

    private val refresher = object : Runnable {
        override fun run() {
            renderUsage()
            handler.postDelayed(this, 30_000L)
        }
    }

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { renderPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (!Prefs.isApproved(this)) {
            startActivity(Intent(this, PairingActivity::class.java))
            finish()
            return
        }
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.padForSystemBarsAndKeyboard()
        b.hello.text = "Chào ${Prefs.childName(this)}! 👋"
        b.askMoreTime.setOnClickListener { askMoreTime() }
        b.send.setOnClickListener { sendMessage() }
    }

    override fun onStart() {
        super.onStart()
        if (!::b.isInitialized) return
        // Dịch vụ Trợ năng có thể đã xoá ghép nối trong lúc màn hình này đang ẩn.
        if (!Prefs.isApproved(this)) {
            backToPairing(REMOVED_MESSAGE)
            return
        }
        Cloud.signIn { uid, _ -> if (uid != null) listen(uid) }
    }

    override fun onResume() {
        super.onResume()
        if (!::b.isInitialized) return
        renderPermissions()
        handler.post(refresher)
        // Báo ngay cho ba mẹ máy đã bật đủ quyền chưa, kể cả khi Trợ năng đang tắt.
        Reporter.state(this, UsageTracker.today(this), null, DeviceChecks.accessibilityOn(this))
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    override fun onStop() {
        listeners.clear()
        super.onStop()
    }

    // ---------- Dữ liệu từ Firebase ----------

    private fun listen(uid: String) {
        val familyId = Prefs.familyId(this) ?: return
        val childId = Prefs.childId(this) ?: return
        val fam = Cloud.family(familyId)
        listeners.clear()

        listeners.on(fam.child("devices/$uid")) { s ->
            val newChild = s.child("childId").getValue(String::class.java)
            when {
                newChild == null -> backToPairing(REMOVED_MESSAGE)
                newChild != childId -> { Prefs.setApproved(this, newChild); recreate() }
                else -> b.deviceLine.text = "Máy: ${s.child("name").getValue(String::class.java) ?: Cloud.deviceName(this)}"
            }
        }
        listeners.on(fam.child("deviceRules/$uid")) { s ->
            rules = Rules.from(s)
            renderUsage()
            renderPermissions()
        }
        listeners.on(fam.child("children/$childId")) { s ->
            s.child("name").getValue(String::class.java)?.let { b.hello.text = "Chào $it! 👋" }
            b.points.text = (s.child("points").getValue(Int::class.java) ?: 0).toString()
        }
        listeners.on(fam.child("schedule/$childId")) { s -> renderSchedule(s) }
        listeners.on(fam.child("messages/$childId").orderByKey().limitToLast(30)) { s -> renderMessages(s) }
    }

    private fun backToPairing(message: String) {
        listeners.clear()
        Prefs.clearPairing(this)
        startActivity(Intent(this, PairingActivity::class.java).putExtra(PairingActivity.EXTRA_MESSAGE, message))
        finish()
    }

    // ---------- Hiển thị ----------

    private fun renderUsage() {
        if (!DeviceChecks.usageAccessOn(this)) {
            b.usageText.text = "Chưa đếm được giờ dùng máy"
            b.usageBar.progress = 0
            b.remainingText.text = "Cần bật quyền \"Truy cập dữ liệu sử dụng\" ở trên."
            return
        }
        val used = UsageTracker.today(this).totalMinutes
        val limit = rules.effectiveLimit
        b.usageText.text = "Con đã dùng $used / $limit phút"
        b.usageBar.progress = if (limit > 0) (used * 100 / limit).coerceIn(0, 100) else 100
        b.usageBar.setIndicatorColor(ContextCompat.getColor(this, if (used >= limit) R.color.warn else R.color.primary))
        b.remainingText.text = when {
            used >= limit -> "Đã hết giờ hôm nay. Con có thể xin ba mẹ thêm giờ."
            rules.bonusToday > 0 -> "Còn ${limit - used} phút (đã có +${rules.bonusToday} phút thưởng)"
            else -> "Còn ${limit - used} phút"
        }
    }

    private data class Missing(val title: String, val hint: String?, val action: () -> Unit)

    private fun renderPermissions() {
        val missing = mutableListOf<Missing>()
        if (!DeviceChecks.accessibilityOn(this)) {
            missing += Missing(
                "Bật Trợ năng cho Sổ Bé Ngoan",
                "Vào Trợ năng › Sổ Bé Ngoan › bật. Nếu nút bật bị mờ: vào Cài đặt › Ứng dụng › Sổ Bé Ngoan › ⋮ › " +
                    "\"Cho phép cài đặt bị hạn chế\" rồi thử lại.",
            ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        if (!DeviceChecks.usageAccessOn(this)) {
            missing += Missing("Cho phép \"Truy cập dữ liệu sử dụng\"", "Chọn Sổ Bé Ngoan trong danh sách rồi bật lên.") {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
        if (!DeviceChecks.notificationsOn(this)) {
            missing += Missing("Cho phép thông báo", "Để con biết khi sắp hết giờ và khi ba mẹ nhắn tin.") {
                if (Build.VERSION.SDK_INT >= 33) {
                    askPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                } else {
                    startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    )
                }
            }
        }
        if (rules.locationEnabled && !DeviceChecks.locationOn(this)) {
            missing += Missing("Cho phép vị trí", "Ba mẹ đã bật xem vị trí của máy này.") {
                askPermissions.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }
        }

        b.permissionsCard.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        b.permissionRows.removeAllViews()
        for (m in missing) {
            b.permissionRows.addView(TextView(this).apply {
                text = m.title
                setTextColor(ContextCompat.getColor(context, R.color.text))
                textSize = 15f
                setPadding(0, dp(12), 0, 0)
            })
            m.hint?.let { hint ->
                b.permissionRows.addView(TextView(this).apply {
                    text = hint
                    setTextColor(ContextCompat.getColor(context, R.color.muted))
                    textSize = 13f
                })
            }
            b.permissionRows.addView(MaterialButton(this).apply {
                text = "Mở cài đặt"
                setOnClickListener { m.action() }
            })
        }
    }

    private fun renderSchedule(s: DataSnapshot) {
        val code = listOf("CN", "T2", "T3", "T4", "T5", "T6", "T7")[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1]
        val label = mapOf("CN" to "Chủ nhật", "T2" to "Thứ 2", "T3" to "Thứ 3", "T4" to "Thứ 4",
            "T5" to "Thứ 5", "T6" to "Thứ 6", "T7" to "Thứ 7")[code]
        b.scheduleTitle.text = "📅 Lịch hôm nay ($label)"
        val items = s.children.mapNotNull { c ->
            val day = c.child("day").getValue(String::class.java)
            val time = c.child("time").getValue(String::class.java) ?: return@mapNotNull null
            val title = c.child("title").getValue(String::class.java) ?: return@mapNotNull null
            if (day == code || day == "daily") time to title else null
        }.sortedBy { it.first }
        b.scheduleRows.removeAllViews()
        if (items.isEmpty()) {
            b.scheduleRows.addView(mutedText("Hôm nay chưa có lịch nào."))
            return
        }
        for ((time, title) in items) {
            b.scheduleRows.addView(TextView(this).apply {
                text = "$time   $title"
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.text))
                setPadding(0, dp(6), 0, dp(6))
            })
        }
    }

    private fun renderMessages(s: DataSnapshot) {
        b.messages.removeAllViews()
        val msgs = s.children.toList()
        if (msgs.isEmpty()) {
            b.messages.addView(mutedText("Chưa có tin nhắn nào."))
            return
        }
        for (m in msgs) {
            val fromChild = m.child("from").getValue(String::class.java) == "child"
            val bubble = TextView(this).apply {
                text = m.child("text").getValue(String::class.java) ?: ""
                textSize = 15f
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = ContextCompat.getDrawable(context, R.drawable.bubble)
                backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, if (fromChild) R.color.bubble_child else R.color.bubble_parent)
                )
                setTextColor(if (fromChild) Color.WHITE else ContextCompat.getColor(context, R.color.text))
                maxWidth = (resources.displayMetrics.widthPixels * 0.7).toInt()
            }
            b.messages.addView(LinearLayout(this).apply {
                gravity = if (fromChild) Gravity.END else Gravity.START
                setPadding(0, dp(3), 0, dp(3))
                addView(bubble)
            })
        }
    }

    // ---------- Thao tác ----------

    private fun askMoreTime() {
        val familyId = Prefs.familyId(this) ?: return
        val uid = Cloud.uid ?: return
        val now = System.currentTimeMillis()
        b.askStatus.visibility = View.VISIBLE
        if (now - Prefs.lastExtensionAt(this) < 5 * 60_000L) {
            b.askStatus.text = "Con vừa xin rồi, đợi ba mẹ trả lời nhé."
            return
        }
        Prefs.setLastExtensionAt(this, now)
        Cloud.family(familyId).child("requests/$uid").push()
            .setValue(mapOf("type" to "extension", "status" to "pending", "at" to ServerValue.TIMESTAMP))
            .addOnSuccessListener { b.askStatus.text = "Đã gửi! Đợi ba mẹ trả lời nhé. 🙏" }
            .addOnFailureListener {
                Prefs.setLastExtensionAt(this, 0)
                b.askStatus.text = "Chưa gửi được, kiểm tra mạng rồi thử lại."
            }
    }

    private fun sendMessage() {
        val text = b.messageInput.text.toString().trim()
        val familyId = Prefs.familyId(this) ?: return
        val childId = Prefs.childId(this) ?: return
        if (text.isEmpty()) return
        b.messageInput.text.clear()
        Cloud.family(familyId).child("messages/$childId").push()
            .setValue(mapOf("from" to "child", "text" to text, "at" to ServerValue.TIMESTAMP))
            .addOnFailureListener { b.messageInput.setText(text) }
    }

    private fun mutedText(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(ContextCompat.getColor(context, R.color.muted))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REMOVED_MESSAGE = "Ba mẹ đã gỡ máy này khỏi Sổ Bé Ngoan. Muốn dùng lại thì ghép nối lại nhé."
    }
}
