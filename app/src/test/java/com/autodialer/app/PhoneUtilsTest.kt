package com.autodialer.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneUtilsTest {
    @Test fun `10 digit gets 91 prefix`() = assertEquals("919075034748", PhoneUtils.normalize("9075034748"))
    @Test fun `plus 91 form`() = assertEquals("919075034748", PhoneUtils.normalize("+919075034748"))
    @Test fun `leading zero form`() = assertEquals("919075034748", PhoneUtils.normalize("09075034748"))
    @Test fun `hyphen form`() = assertEquals("919075034748", PhoneUtils.normalize("91-9075034748"))
    @Test fun `spaces and brackets`() = assertEquals("919075034748", PhoneUtils.normalize("(+91) 90750 34748"))
    @Test fun `0091 form`() = assertEquals("919075034748", PhoneUtils.normalize("00919075034748"))
    @Test fun `091 form`() = assertEquals("919075034748", PhoneUtils.normalize("0919075034748"))
}
