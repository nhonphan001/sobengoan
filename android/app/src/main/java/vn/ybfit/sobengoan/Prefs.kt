package vn.ybfit.sobengoan

import android.content.Context
import android.content.SharedPreferences

/**
 * Lưu trên máy: gia đình nào, bé nào, và các mốc "đã xử lý tới đâu" để không
 * báo trùng thông báo / chụp trùng ảnh sau khi app khởi động lại.
 *
 * Trạng thái ghép nối:
 *  - familyId = null                → chưa ghép nối (màn hình nhập mã)
 *  - familyId != null, childId null → đã gửi yêu cầu, chờ ba mẹ duyệt
 *  - childId != null                → đã được duyệt
 */
object Prefs {
    private const val FAMILY_ID = "family_id"
    private const val CHILD_NAME = "child_name"
    private const val CHILD_ID = "child_id"
    private const val LAST_SCREENSHOT_AT = "last_screenshot_at"
    private const val LAST_BONUS_AT = "last_bonus_at"
    private const val LAST_PARENT_MSG_AT = "last_parent_msg_at"
    private const val LAST_EXTENSION_AT = "last_extension_at"

    private fun p(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("sobengoan", Context.MODE_PRIVATE)

    fun familyId(c: Context): String? = p(c).getString(FAMILY_ID, null)
    fun childName(c: Context): String = p(c).getString(CHILD_NAME, "") ?: ""
    fun childId(c: Context): String? = p(c).getString(CHILD_ID, null)
    fun isApproved(c: Context) = familyId(c) != null && childId(c) != null

    fun startPairing(c: Context, familyId: String, childName: String) {
        p(c).edit().putString(FAMILY_ID, familyId).putString(CHILD_NAME, childName).remove(CHILD_ID).apply()
    }

    fun setApproved(c: Context, childId: String) {
        p(c).edit().putString(CHILD_ID, childId).apply()
    }

    /** Bị từ chối, bị xoá khỏi gia đình, hoặc tự huỷ: quay về màn hình nhập mã. */
    fun clearPairing(c: Context) {
        p(c).edit().remove(FAMILY_ID).remove(CHILD_ID).apply()
    }

    fun getLong(c: Context, key: String) = p(c).getLong(key, 0L)
    fun putLong(c: Context, key: String, value: Long) = p(c).edit().putLong(key, value).apply()

    fun lastScreenshotAt(c: Context) = getLong(c, LAST_SCREENSHOT_AT)
    fun setLastScreenshotAt(c: Context, v: Long) = putLong(c, LAST_SCREENSHOT_AT, v)
    fun lastBonusAt(c: Context) = getLong(c, LAST_BONUS_AT)
    fun setLastBonusAt(c: Context, v: Long) = putLong(c, LAST_BONUS_AT, v)
    fun lastParentMsgAt(c: Context) = getLong(c, LAST_PARENT_MSG_AT)
    fun setLastParentMsgAt(c: Context, v: Long) = putLong(c, LAST_PARENT_MSG_AT, v)
    fun lastExtensionAt(c: Context) = getLong(c, LAST_EXTENSION_AT)
    fun setLastExtensionAt(c: Context, v: Long) = putLong(c, LAST_EXTENSION_AT, v)

    /** Đánh dấu "đã báo" theo ngày, ví dụ cảnh báo sắp hết giờ chỉ báo 1 lần/ngày. */
    fun markOncePerDay(c: Context, key: String, day: String): Boolean {
        val full = "once_${day}_$key"
        if (p(c).getBoolean(full, false)) return false
        p(c).edit().putBoolean(full, true).apply()
        return true
    }
}
