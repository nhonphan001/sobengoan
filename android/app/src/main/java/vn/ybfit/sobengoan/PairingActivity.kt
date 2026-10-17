package vn.ybfit.sobengoan

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.ServerValue
import vn.ybfit.sobengoan.databinding.ActivityPairingBinding

/**
 * Ghép nối máy với gia đình: nhập Mã gia đình + tên bé -> gửi yêu cầu -> chờ ba
 * mẹ bấm Duyệt trên dashboard (ba mẹ chọn máy này là của bé nào).
 */
class PairingActivity : AppCompatActivity() {

    private lateinit var b: ActivityPairingBinding
    private val listeners = Listeners()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityPairingBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.padForSystemBarsAndKeyboard()

        intent.getStringExtra(EXTRA_MESSAGE)?.let(::showMessage)
        b.submit.setOnClickListener { submit() }
        b.cancel.setOnClickListener { cancel() }

        if (Prefs.familyId(this) != null) showWaiting() else showForm()
    }

    override fun onDestroy() {
        listeners.clear()
        super.onDestroy()
    }

    private fun showForm() {
        listeners.clear()
        b.form.visibility = View.VISIBLE
        b.waiting.visibility = View.GONE
        b.submit.isEnabled = true
        if (b.childName.text.isNullOrEmpty()) b.childName.setText(Prefs.childName(this))
    }

    private fun showMessage(text: String?) {
        b.message.text = text ?: ""
        b.message.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun submit() {
        val code = b.familyCode.text?.toString()?.trim()?.uppercase().orEmpty()
        val name = b.childName.text?.toString()?.trim().orEmpty()
        if (!Regex("[A-Z0-9]{6}").matches(code)) { showMessage("Mã gia đình gồm 6 chữ và số, ví dụ 9VGDVW."); return }
        if (name.isEmpty()) { showMessage("Nhập tên của con nhé."); return }
        showMessage(null)
        b.submit.isEnabled = false
        currentFocus?.clearFocus()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(b.root.windowToken, 0)

        Cloud.signIn { uid, error ->
            if (uid == null) {
                showMessage("Không kết nối được, kiểm tra mạng rồi thử lại. ($error)")
                b.submit.isEnabled = true
                return@signIn
            }
            val fam = Cloud.family(code)
            val request = mapOf("childName" to name, "model" to Cloud.deviceName(this), "requestedAt" to ServerValue.TIMESTAMP)
            fam.child("pairing/$uid").setValue(request)
                .addOnSuccessListener {
                    Prefs.startPairing(this, code, name)
                    showWaiting()
                }
                .addOnFailureListener {
                    // Bị từ chối ghi khi mã sai, hoặc khi máy này đã thuộc gia đình rồi.
                    fam.child("devices/$uid/childId").get()
                        .addOnSuccessListener { s ->
                            val childId = s.getValue(String::class.java)
                            if (childId != null) {
                                Prefs.startPairing(this, code, name)
                                Prefs.setApproved(this, childId)
                                openMain()
                            } else {
                                showMessage("Không gửi được yêu cầu. Kiểm tra lại Mã gia đình.")
                                b.submit.isEnabled = true
                            }
                        }
                        .addOnFailureListener {
                            showMessage("Không gửi được yêu cầu. Kiểm tra lại Mã gia đình.")
                            b.submit.isEnabled = true
                        }
                }
        }
    }

    private fun showWaiting() {
        val familyId = Prefs.familyId(this) ?: return showForm()
        b.form.visibility = View.GONE
        b.waiting.visibility = View.VISIBLE
        b.waitingText.text = "Đã gửi yêu cầu vào gia đình $familyId với tên \"${Prefs.childName(this)}\".\n\n" +
            "Nhờ ba mẹ mở trang Sổ Bé Ngoan và bấm Duyệt. Màn hình này sẽ tự chuyển khi được duyệt."

        Cloud.signIn { uid, _ ->
            if (uid == null) return@signIn
            val fam = Cloud.family(familyId)
            listeners.clear()
            listeners.on(fam.child("devices/$uid/childId")) { s ->
                val childId = s.getValue(String::class.java) ?: return@on
                Prefs.setApproved(this, childId)
                openMain()
            }
            // Yêu cầu biến mất mà máy không được duyệt = ba mẹ đã từ chối.
            listeners.on(fam.child("pairing/$uid")) { s ->
                if (s.exists()) return@on
                fam.child("devices/$uid/childId").get().addOnSuccessListener { d ->
                    if (d.exists() || Prefs.familyId(this) == null) return@addOnSuccessListener
                    Prefs.clearPairing(this)
                    showForm()
                    showMessage("Ba mẹ đã từ chối yêu cầu ghép nối. Kiểm tra lại mã và tên rồi gửi lại nhé.")
                }
            }
        }
    }

    private fun cancel() {
        val familyId = Prefs.familyId(this)
        val uid = Cloud.uid
        listeners.clear()
        Prefs.clearPairing(this)
        if (familyId != null && uid != null) Cloud.family(familyId).child("pairing/$uid").removeValue()
        showForm()
    }

    private fun openMain() {
        listeners.clear()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        const val EXTRA_MESSAGE = "message"
    }
}
