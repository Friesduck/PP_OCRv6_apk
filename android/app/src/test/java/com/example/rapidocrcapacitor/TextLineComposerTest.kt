package com.example.rapidocrcapacitor

import org.junit.Assert.assertEquals
import org.junit.Test

class TextLineComposerTest {
    @Test
    fun mergesBoxesOnTheSameVisualLineFromLeftToRight() {
        val result = TextLineComposer.compose(
            listOf(
                PositionedText("张三", left = 180, top = 102, bottom = 138),
                PositionedText("姓名", left = 20, top = 100, bottom = 140),
                PositionedText("男", left = 360, top = 106, bottom = 136),
            ),
        )

        assertEquals(listOf("姓名 张三 男"), result)
    }

    @Test
    fun keepsVerticallySeparatedBoxesOnDifferentLines() {
        val result = TextLineComposer.compose(
            listOf(
                PositionedText("姓名 张三", left = 20, top = 100, bottom = 140),
                PositionedText("住址 北京市", left = 20, top = 170, bottom = 210),
                PositionedText("公民身份号码", left = 20, top = 240, bottom = 280),
            ),
        )

        assertEquals(
            listOf("姓名 张三", "住址 北京市", "公民身份号码"),
            result,
        )
    }

    @Test
    fun mergesBoxesThatOverlapDespiteSmallVerticalOffset() {
        val result = TextLineComposer.compose(
            listOf(
                PositionedText("签发机关", left = 20, top = 300, bottom = 342),
                PositionedText("公安局", left = 200, top = 314, bottom = 350),
            ),
        )

        assertEquals(listOf("签发机关 公安局"), result)
    }
}
