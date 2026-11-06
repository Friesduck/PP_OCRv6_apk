package com.example.rapidocrcapacitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidityPeriodParserTest {
    @Test
    fun extractsBothExactFormatsInOrderAndDeduplicates() {
        val lines = listOf(
            "有效期限 2020.08.20-2030.08.20",
            "换证记录 2030.08.21-2040.08.21",
            "长期证件 2024.01.01-长期",
            "重复 2020.08.20-2030.08.20",
        )

        assertEquals(
            listOf(
                "20200820-20300820",
                "20300821-20400821",
                "20240101-长期",
            ),
            ValidityPeriodParser.extract(lines),
        )
    }

    @Test
    fun rejectsAnythingOutsideTheExactCharacterShapes() {
        val lines = listOf(
            "斜杠 2020/08/20-2030/08/20",
            "空格 2020.08.20 - 2030.08.20",
            "短位数 2020.8.20-2030.08.20",
            "全角 ２０２０.０８.２０-２０３０.０８.２０",
            "长期空格 2020.08.20-长 期",
        )

        assertTrue(ValidityPeriodParser.extract(lines).isEmpty())
    }

    @Test
    fun doesNotValidateCalendarValuesOrDateOrder() {
        assertEquals(
            listOf("20219999-20000000"),
            ValidityPeriodParser.extract(listOf("期限2021.99.99-2000.00.00")),
        )
    }

    @Test
    fun rejectsMatchesEmbeddedInLongerDigitRuns() {
        assertTrue(
            ValidityPeriodParser.extract(
                listOf("12020.08.20-2030.08.200"),
            ).isEmpty(),
        )
    }
}
