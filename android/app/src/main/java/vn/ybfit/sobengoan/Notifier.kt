package vn.ybfit.sobengoan

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Thông báo trên máy của bé. Bấm vào thông báo nào cũng mở Sổ Bé Ngoan. */
object Notifier {
    private const val CH_LIMITS = "limits"
    private const val CH_MESSAGES = "messages"

    private const val ID_BLOCKED = 1
    private const val ID_WARNING = 2
    private const val ID_MESSAGE = 3
    private const val ID_BONUS = 4

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_LIMITS, "Giờ dùng máy", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Sắp hết giờ, hết giờ, ứng dụng bị chặn, được thưởng thêm giờ" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "Tin nhắn của ba mẹ", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun blocked(c: Context, text: String) = show(c, CH_LIMITS, ID_BLOCKED, "Sổ Bé Ngoan", text)
    fun warning(c: Context, text: String) = show(c, CH_LIMITS, ID_WARNING, "Sắp hết giờ", text)
    fun message(c: Context, text: String) = show(c, CH_MESSAGES, ID_MESSAGE, "Ba mẹ nhắn", text)
    fun bonus(c: Context, minutes: Int) =
        show(c, CH_LIMITS, ID_BONUS, "🎉 Được thưởng thêm giờ!", "Ba mẹ vừa cho con thêm $minutes phút. Dùng máy thật vui nhé!")

    @SuppressLint("MissingPermission") // đã kiểm tra areNotificationsEnabled()
    private fun show(c: Context, channel: String, id: Int, title: String, text: String) {
        val nm = NotificationManagerCompat.from(c)
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            c, 0,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(id, n)
    }
}
