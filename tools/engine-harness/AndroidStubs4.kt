package android.app
import android.content.Intent
import android.telephony.TelephonyManager
open class Activity {
    companion object { const val TELEPHONY_SERVICE = "phone" }
    val telephony = TelephonyManager()
    val dialed = java.util.Collections.synchronizedList(mutableListOf<String>())
    var failDial: RuntimeException? = null
    fun getSystemService(name: String): Any? = telephony
    fun startActivity(i: Intent) { failDial?.let { throw it }; dialed.add(i.data.toString().removePrefix("tel:")) }
}
