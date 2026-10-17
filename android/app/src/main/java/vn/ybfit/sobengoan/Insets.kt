package vn.ybfit.sobengoan

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Android 15+ luôn vẽ tràn dưới thanh trạng thái/điều hướng và không tự đẩy nội
 * dung lên khi bàn phím mở. Chừa đúng khoảng của các thanh hệ thống và bàn phím.
 * Gắn vào khung NGOÀI vùng cuộn: vùng cuộn co lại thì tự cuộn tới ô đang gõ.
 */
fun View.padForSystemBarsAndKeyboard() {
    val base = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.setPadding(
            base[0] + bars.left, base[1] + bars.top, base[2] + bars.right,
            base[3] + maxOf(bars.bottom, ime.bottom),
        )
        insets
    }
}
