package vn.ybfit.sobengoan

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Đường dẫn dữ liệu trên Firebase — khớp với database.rules.json và dashboard
 * (web/app.js). Mỗi máy là một deviceUid (UID đăng nhập ẩn danh của máy).
 */
object Cloud {
    private val db get() = FirebaseDatabase.getInstance()

    val uid: String? get() = FirebaseAuth.getInstance().currentUser?.uid

    fun family(familyId: String): DatabaseReference = db.getReference("families/$familyId")

    /** Đăng nhập ẩn danh (giữ nguyên UID qua các lần mở app, mất khi gỡ app). */
    fun signIn(onResult: (uid: String?, error: String?) -> Unit) {
        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.let { onResult(it.uid, null); return }
        auth.signInAnonymously()
            .addOnSuccessListener { onResult(it.user?.uid, null) }
            .addOnFailureListener { onResult(null, it.message) }
    }

    /** Khoá Firebase không được chứa ".", nên tên gói lưu với "," thay cho ".". */
    fun pkgKey(pkg: String) = pkg.replace('.', ',')
    fun pkgFromKey(key: String) = key.replace(',', '.')

    fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Tên máy dễ đọc: tên người dùng đặt cho máy, nếu không có thì hãng + đời máy. */
    fun deviceName(c: Context): String {
        val named = Settings.Global.getString(c.contentResolver, Settings.Global.DEVICE_NAME)
        if (!named.isNullOrBlank()) return named.take(40)
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return (if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model").take(40)
    }
}

/** Gom các listener Firebase để huỷ cùng lúc (khi đổi gia đình, bị xoá, hay đóng màn hình). */
class Listeners {
    private val active = mutableListOf<Pair<Query, ValueEventListener>>()

    fun on(query: Query, onError: (DatabaseError) -> Unit = {}, onData: (DataSnapshot) -> Unit) {
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = onData(snapshot)
            override fun onCancelled(error: DatabaseError) = onError(error)
        }
        query.addValueEventListener(l)
        active += query to l
    }

    fun clear() {
        active.forEach { (q, l) -> q.removeEventListener(l) }
        active.clear()
    }
}
