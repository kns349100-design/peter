package com.autodialer.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DurableFileTest {
    private fun tmp() = java.nio.file.Files.createTempDirectory("df").toFile()

    @Test fun `write then read round-trips and overwrites`() {
        val f = File(tmp(), "s.json")
        assertTrue(DurableFile.writeAtomic(f, "one".toByteArray())); assertEquals("one", String(DurableFile.readBytes(f)!!))
        assertTrue(DurableFile.writeAtomic(f, "two".toByteArray())); assertEquals("two", String(DurableFile.readBytes(f)!!))
        assertFalse("no temp file left behind", File(f.path + ".tmp").exists())
    }
    @Test fun `half-written temp file is ignored while the real file exists`() {
        val f = File(tmp(), "s.json"); DurableFile.writeAtomic(f, "GOOD".toByteArray())
        File(f.path + ".tmp").writeText("PART")                       // crash in the middle of the next write
        assertEquals("GOOD", String(DurableFile.readBytes(f)!!))
    }
    @Test fun `finished temp file is used when the real file is missing`() {
        val f = File(tmp(), "s.json"); File(f.path + ".tmp").writeText("DONE")
        assertEquals("DONE", String(DurableFile.readBytes(f)!!)); assertTrue(DurableFile.exists(f))
    }
    @Test fun `missing file reads as null and delete removes both`() {
        val f = File(tmp(), "s.json"); assertNull(DurableFile.readBytes(f)); assertFalse(DurableFile.exists(f))
        DurableFile.writeAtomic(f, "x".toByteArray()); File(f.path + ".tmp").writeText("y")
        DurableFile.delete(f); assertFalse(DurableFile.exists(f))
    }
    @Test(timeout = 30_000) fun `a reader never sees a torn file while a writer keeps replacing it`() {
        val f = File(tmp(), "s.json"); DurableFile.writeAtomic(f, "a".repeat(1000).toByteArray())
        val stop = AtomicBoolean(false); val torn = AtomicInteger(); val reads = AtomicInteger()
        val reader = Thread { while (!stop.get()) {
            val b = DurableFile.readBytes(f) ?: continue; val s = String(b); reads.incrementAndGet()
            if (s.isEmpty() || s.any { it != s[0] }) torn.incrementAndGet()
        } }
        reader.start()
        for (i in 0 until 400) DurableFile.writeAtomic(f, ((('a' + i % 26)).toString().repeat(1000 + i * 50)).toByteArray())
        stop.set(true); reader.join()
        assertTrue(reads.get() > 0); assertEquals(0, torn.get())
    }
}
