package android.telephony
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.ExecutorService
open class TelephonyCallback { interface CallStateListener { fun onCallStateChanged(state: Int) } }
open class PhoneStateListener {
    constructor()
    constructor(@Suppress("UNUSED_PARAMETER") e: Executor)
    companion object { const val LISTEN_NONE = 0; const val LISTEN_CALL_STATE = 32 }
    open fun onCallStateChanged(state: Int, phoneNumber: String?) {}
}
open class TelephonyManager {
    companion object { const val CALL_STATE_IDLE = 0; const val CALL_STATE_RINGING = 1; const val CALL_STATE_OFFHOOK = 2 }
    @Volatile var callState: Int = 0
    @Volatile var cb: TelephonyCallback? = null
    @Volatile var exec: Executor? = null
    @Volatile var failRegister: RuntimeException? = null
    fun registerTelephonyCallback(e: Executor, c: TelephonyCallback) { failRegister?.let { throw it }; exec = e; cb = c }
    fun unregisterTelephonyCallback(c: TelephonyCallback) { if (cb === c) cb = null }
    fun listen(l: PhoneStateListener, events: Int) {}
    /** The OS delivering a state change on the executor thread. Returns when it has been delivered. */
    fun emit(state: Int) {
        val c = cb as? TelephonyCallback.CallStateListener ?: return
        val e = exec as ExecutorService
        e.submit { c.onCallStateChanged(state) }.get()
    }
}
