package com.autodialer.app

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SafeLogHarnessTest {
    private val seen = mutableListOf<String>()
    @Before fun setUp() { seen.clear(); SafeLog.debugEnabled = false; SafeLog.sink = { p, t, m -> seen.add("$p/$t/$m") } }
    @After fun tearDown() { SafeLog.debugEnabled = false }

    @Test fun `debug and info print nothing unless enabled`() { SafeLog.d("T", "x 9075034748"); SafeLog.i("T", "y"); assertTrue(seen.isEmpty()) }
    @Test fun `when enabled the message is still scrubbed`() { SafeLog.debugEnabled = true; SafeLog.d("T", "Calling +91 90750 34748"); assertEquals(1, seen.size); assertFalse(seen[0].contains("90750")) }
    @Test fun `warnings and errors always print, scrubbed, with the exception type but no raw numbers`() {
        SafeLog.w("T", "lead 9075034748 failed"); SafeLog.e("T", "boom", IllegalStateException("bad number 9075034748"))
        assertEquals(2, seen.size); assertTrue(seen.none { Regex("""\d{7,}""").containsMatchIn(it) }); assertTrue(seen[1].contains("IllegalStateException"))
    }
}
