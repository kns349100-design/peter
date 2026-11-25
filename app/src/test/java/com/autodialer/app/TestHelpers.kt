package com.autodialer.app

/** What the engine does on a real call: OFFHOOK (connected) then IDLE (ended). Returns onCallEnded()'s result. */
fun CallSequencer.connectAndEnd(): Boolean {
    onCallConnected()
    return onCallEnded()
}
