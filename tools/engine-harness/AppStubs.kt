package com.autodialer.app
import java.util.concurrent.CopyOnWriteArrayList

data class CallLogEntry(val time: String, val name: String?, val phone: String, val status: String, val outcome: String?, val note: String? = null, val collected: Boolean = false)

class CallLogStore {
    private val phones = HashSet<String>(); val entries = CopyOnWriteArrayList<CallLogEntry>()
    fun todayKey() = "2026-11-19"; fun nowTime() = "10:00"
    fun addEntry(date: String, e: CallLogEntry): Int { synchronized(phones) { phones.add(PhoneUtils.normalize(e.phone)) }; entries.add(e); return entries.size - 1 }
    fun updateEntry(date: String, index: Int, status: String, outcome: String?) {}
    fun calledToday(n: String) = synchronized(phones) { phones.contains(n) }
    fun preload(vararg p: String) = synchronized(phones) { p.forEach { phones.add(PhoneUtils.normalize(it)) } }
}
