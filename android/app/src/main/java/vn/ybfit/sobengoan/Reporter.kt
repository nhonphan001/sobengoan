package vn.ybfit.sobengoan

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.ServerValue

/** Gửi tình trạng của máy lên cho ba mẹ (deviceState, usage, labels, alerts, location). */
object Reporter {
    private val labelled = HashSet<String>() // app đã gửi tên hiển thị trong lần chạy này
    private var lastLocationAt = 0L
    private const val LOCATION_INTERVAL_MS = 5 * 60 * 1000L

    private fun target(c: Context): Pair<String, String>? {
        if (!Prefs.isApproved(c)) return null
        val familyId = Prefs.familyId(c) ?: return null
        val uid = Cloud.uid ?: return null
        return familyId to uid
    }

    /**
     * Tín hiệu "đang online" kèm thông tin máy. accessibilityOn cho ba mẹ biết máy
     * có đang thật sự bị giới hạn không (chưa bật Trợ năng thì không chặn được gì).
     */
    fun state(c: Context, usage: UsageTracker.Today?, currentApp: String?, accessibilityOn: Boolean) {
        val (familyId, uid) = target(c) ?: return
        val st = "deviceState/$uid"
        val updates = hashMapOf<String, Any?>(
            "$st/lastSeen" to ServerValue.TIMESTAMP,
            "$st/model" to Cloud.deviceName(c),
            "$st/androidVersion" to Build.VERSION.RELEASE,
            "$st/appVersion" to BuildConfig.VERSION_NAME,
            "$st/battery" to DeviceChecks.batteryPercent(c),
            "$st/accessibilityOn" to accessibilityOn,
            "$st/usageAccessOn" to DeviceChecks.usageAccessOn(c),
        )
        if (usage != null) {
            updates["$st/todayMinutes"] = usage.totalMinutes
            updates["usage/$uid/${Cloud.todayKey()}"] = usage.minutesByPkg.mapKeys { Cloud.pkgKey(it.key) }
            for (pkg in usage.minutesByPkg.keys) {
                if (labelled.add(pkg)) {
                    DeviceChecks.appLabel(c, pkg)?.let { updates["labels/$uid/${Cloud.pkgKey(pkg)}"] = it.take(40) }
                }
            }
        }
        if (currentApp != null) {
            updates["$st/currentApp"] = mapOf("pkg" to currentApp, "at" to ServerValue.TIMESTAMP)
        }
        Cloud.family(familyId).updateChildren(updates)
    }

    fun alert(c: Context, type: String, pkg: String? = null) {
        val (familyId, uid) = target(c) ?: return
        val data = hashMapOf<String, Any>("type" to type, "at" to ServerValue.TIMESTAMP)
        if (pkg != null) data["pkg"] = pkg
        Cloud.family(familyId).child("alerts/$uid").push().setValue(data)
    }

    fun screenshot(c: Context, base64Jpeg: String) {
        val (familyId, uid) = target(c) ?: return
        Cloud.family(familyId).child("screenshot/$uid")
            .setValue(mapOf("image" to base64Jpeg, "at" to ServerValue.TIMESTAMP))
    }

    /** Vị trí gần nhất đã biết (không bật GPS liên tục cho đỡ hao pin), tối đa 5 phút/lần. */
    @SuppressLint("MissingPermission") // đã kiểm tra DeviceChecks.locationOn()
    fun locationIfDue(c: Context) {
        val (familyId, uid) = target(c) ?: return
        val now = System.currentTimeMillis()
        if (now - lastLocationAt < LOCATION_INTERVAL_MS || !DeviceChecks.locationOn(c)) return
        // Máy không có Google Play Services (vd một số máy Huawei) thì bỏ qua định vị.
        val gms = try {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(c)
        } catch (t: Throwable) {
            ConnectionResult.SERVICE_MISSING
        }
        if (gms != ConnectionResult.SUCCESS) return
        lastLocationAt = now
        LocationServices.getFusedLocationProviderClient(c).lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                Cloud.family(familyId).child("location/$uid").setValue(
                    mapOf("lat" to loc.latitude, "lng" to loc.longitude, "at" to ServerValue.TIMESTAMP)
                )
            }
        }
    }
}
