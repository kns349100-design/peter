package android.content
import android.net.Uri
class Intent(val action: String) { companion object { const val ACTION_CALL = "android.intent.action.CALL" }; var data: Uri? = null }
