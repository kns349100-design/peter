package com.autodialer.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch

class DialJournalTest {
    private fun file() = File(java.nio.file.Files.createTempDirectory("j").toFile(), "j.log")
    private val cipher = TestCiphers.cipher()
    private fun DialJournal(f: File) = com.autodialer.app.DialJournal(f, cipher)

    @Test fun `records round-trip in order`() {
        val j = DialJournal(file())
        assertTrue(j.append("s1", JournalType.DIAL, 0, "9111111111")); assertTrue(j.append("s1", JournalType.SKIP, 3, "9222222222"))
        assertTrue(j.append("s1", JournalType.ABORT, 0, "9111111111"))
        assertEquals(listOf(JournalType.DIAL, JournalType.SKIP, JournalType.ABORT), j.read("s1").map { it.type })
        assertEquals(listOf(0, 3, 0), j.read("s1").map { it.index })
    }
    @Test fun `records of another session are ignored`() {
        val j = DialJournal(file()); j.append("old", JournalType.DIAL, 1, "9111111111")
        assertTrue(j.read("new").isEmpty())
    }
    @Test fun `empty session id is refused so nothing is ever dialed without a valid journal`() {
        assertFalse(DialJournal(file()).append("", JournalType.DIAL, 1, "9111111111"))
    }
    @Test fun `torn last line is ignored and the next append is not glued onto it`() {
        val f = file(); val j = DialJournal(f)
        j.append("s", JournalType.DIAL, 0, "9111111111")
        f.appendText("J1|s|99|D|1|92222")                              // power cut mid-write, no newline
        assertEquals(1, j.read("s").size)
        assertTrue(j.append("s", JournalType.DIAL, 2, "9333333333"))
        assertEquals(listOf(0, 2), j.read("s").map { it.index })       // the new record is intact
    }
    @Test fun `corrupted line in the middle is skipped, the rest survives`() {
        val f = file(); val j = DialJournal(f)
        j.append("s", JournalType.DIAL, 0, "9111111111"); j.append("s", JournalType.DIAL, 1, "9222222222"); j.append("s", JournalType.DIAL, 2, "9333333333")
        val lines = f.readLines().toMutableList()
        val l = lines[1]; val pos = l.length / 2; lines[1] = l.substring(0, pos) + (if (l[pos] == '0') '1' else '0') + l.substring(pos + 1)
        f.writeText(lines.joinToString("\n") + "\n")
        assertEquals(listOf(0, 2), j.read("s").map { it.index })
    }
    @Test fun `phone with separators cannot break the format`() {
        val j = DialJournal(file()); assertTrue(j.append("s", JournalType.DIAL, 0, "+91|98\n765"))
        assertEquals(1, j.read("s").size)
    }
    @Test fun `unwritable journal reports failure`() {
        val f = file(); f.mkdirs()                                     // a directory where the file should be
        assertFalse(DialJournal(f).append("s", JournalType.DIAL, 0, "9111111111"))
    }
    @Test fun `delete clears everything`() {
        val f = file(); val j = DialJournal(f); j.append("s", JournalType.DIAL, 0, "9111111111"); j.delete()
        assertTrue(j.read("s").isEmpty())
    }
    @Test(timeout = 30_000) fun `eight threads appending 100 records each - all 800 intact`() {
        val j = DialJournal(file()); val go = CountDownLatch(1)
        val ts = (0 until 8).map { t -> Thread { go.await(); for (i in 0 until 100) j.append("s", JournalType.DIAL, t * 100 + i, "9%09d".format(t * 100 + i)) }.also { it.start() } }
        go.countDown(); ts.forEach { it.join() }
        assertEquals(800, j.read("s").map { it.index }.toSet().size)
    }
}
