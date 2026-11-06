package com.example.rapidocrcapacitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardPhoneParserTest {
    @Test
    fun extractsMainlandPhoneWithOptionalCountryCodeAndSeparators() {
        assertEquals("13800138000", ClipboardPhoneParser.extract("联系人：+86 138-0013-8000"))
    }

    @Test
    fun ignoresNonPhoneClipboardContent() {
        assertNull(ClipboardPhoneParser.extract("订单号 123456789012345"))
        assertNull(ClipboardPhoneParser.extract("固定电话 010-12345678"))
    }
}
