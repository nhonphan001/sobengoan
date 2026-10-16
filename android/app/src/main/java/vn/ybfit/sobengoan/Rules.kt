package vn.ybfit.sobengoan

import com.google.firebase.database.DataSnapshot

/** Giới hạn ba mẹ đặt cho máy này (families/{id}/deviceRules/{deviceUid}). */
data class Rules(
    val dailyLimit: Int = DEFAULT_DAILY_LIMIT,
    val bonusToday: Int = 0,
    val blocked: Set<String> = emptySet(),      // tên gói
    val appLimits: Map<String, Int> = emptyMap(), // tên gói -> phút/ngày
    val locationEnabled: Boolean = false,
) {
    val effectiveLimit get() = dailyLimit + bonusToday

    companion object {
        const val DEFAULT_DAILY_LIMIT = 120

        fun from(s: DataSnapshot): Rules = Rules(
            dailyLimit = s.child("dailyLimit").getValue(Int::class.java) ?: DEFAULT_DAILY_LIMIT,
            bonusToday = s.child("bonus").child(Cloud.todayKey()).getValue(Int::class.java) ?: 0,
            blocked = s.child("blocked").children
                .filter { it.getValue(Boolean::class.java) == true }
                .mapNotNull { it.key?.let(Cloud::pkgFromKey) }
                .toSet(),
            appLimits = s.child("appLimits").children.mapNotNull { e ->
                val minutes = e.getValue(Int::class.java)
                val key = e.key
                if (key != null && minutes != null && minutes > 0) Cloud.pkgFromKey(key) to minutes else null
            }.toMap(),
            locationEnabled = s.child("locationEnabled").getValue(Boolean::class.java) ?: false,
        )
    }
}
