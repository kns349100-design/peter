package com.autodialer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberParserTest {
    @Test fun `plain number found`() =
        assertEquals(setOf("9075034748"), NumberParser.extractPhoneNumbers("call 9075034748 now"))

    @Test fun `plus 91 with space reported once`() {
        val r = NumberParser.extractPhoneNumbers("+91 9075034748")
        assertEquals(1, r.size)
    }

    @Test fun `same number in two formats is deduped`() {
        val r = NumberParser.extractPhoneNumbers("9075034748, +91 9075034748")
        assertEquals(1, r.size)
    }

    @Test fun `hyphen and space separated`() {
        val r = NumberParser.extractPhoneNumbers("90750-34748 and 98765 43210")
        assertEquals(2, r.size)
    }

    @Test fun `short codes are ignored`() =
        assertTrue(NumberParser.extractPhoneNumbers("OTP 1234 and 567890").isEmpty())

    @Test fun `empty text`() = assertTrue(NumberParser.extractPhoneNumbers("").isEmpty())

    @Test fun `name before number becomes label`() {
        val l = NumberParser.extractLeadsFromLine("Rahul 9075034748", 1)
        assertEquals(1, l.size)
        assertEquals("Rahul", l[0].name)
        assertEquals("9075034748", l[0].phone)
    }

    @Test fun `name after number becomes label`() {
        val l = NumberParser.extractLeadsFromLine("9075034748, Priya", 1)
        assertEquals("Priya", l[0].name)
    }

    @Test fun `no name gets auto label`() {
        val l = NumberParser.extractLeadsFromLine("9075034748", 7)
        assertEquals("Lead 7", l[0].name)
    }

    @Test fun `two numbers one name get numbered labels`() {
        val l = NumberParser.extractLeadsFromLine("Amit 9075034748 9876543210", 1)
        assertEquals(2, l.size)
        assertEquals("Amit 1", l[0].name)
        assertEquals("Amit 2", l[1].name)
    }

    @Test fun `line without number returns empty`() =
        assertTrue(NumberParser.extractLeadsFromLine("just text", 1).isEmpty())
}

class NumberParserLabelTest {
    @Test fun `spaced number does not leak into the name`() {
        val l = NumberParser.extractLeadsFromLine("Priya +91 98765 43210", 1)
        assertEquals("Priya", l[0].name)
    }
    @Test fun `hyphen separated name and leading zero number`() {
        val l = NumberParser.extractLeadsFromLine("Amit - 090750 11111", 1)
        assertEquals("Amit", l[0].name)
    }
    @Test fun `bracketed number keeps name clean`() {
        val l = NumberParser.extractLeadsFromLine("Sunita (+91) 90750 34748", 1)
        assertEquals("Sunita", l[0].name)
    }
}
