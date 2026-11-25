package com.zeroui.template.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportParserTest {

    private val sample = """
        这段聊天以工作安排为主，双方配合默契。

        > [!WARNING] 截止日期临近
        > 周五前要交方案，目前还没定稿。

        > [!TIP] 分工明确
        > 双方各自负责一半内容。

        ### 时间线
        - **2024-05-01 20:30** 约定周五交方案
        - **2024-05-03 09:12** 对方催进度
          > 依据：对方说"今天能给吗"

        ### 待办事项
        - **提交方案初稿**
          - 提出者：张三
          - 期限：未明确
          - 状态：未完成
          - 依据：05-01 原话

        ### 主要话题
        **工作**
        讨论最多的是项目进度。
        - 方案修改
        - 会议安排

        > 这事包在我身上 —— 张三
    """.trimIndent()

    @Test
    fun parsesEveryKindOfBlock() {
        val blocks = ReportParser.parse(sample)
        val kinds = blocks.map { it::class.simpleName }
        assertEquals(
            listOf(
                "Paragraph", "Callouts",
                "Heading", "Timeline",
                "Heading", "ItemCard",
                "Heading", "SubTitle", "Paragraph", "Bullets", "Quote",
            ),
            kinds,
        )
        val callouts = blocks[1] as ReportBlock.Callouts
        assertEquals(listOf(CalloutKind.Warning, CalloutKind.Tip), callouts.items.map { it.kind })
        assertEquals("截止日期临近", callouts.items[0].title)
        assertEquals("周五前要交方案，目前还没定稿。", callouts.items[0].body)

        val timeline = blocks[3] as ReportBlock.Timeline
        assertEquals(listOf("2024-05-01 20:30", "2024-05-03 09:12"), timeline.items.map { it.time })
        assertTrue(timeline.items[1].notes.single().quote)

        val card = blocks[5] as ReportBlock.ItemCard
        assertEquals("**提交方案初稿**", card.title)
        assertEquals(listOf("提出者", "期限", "状态", "依据"), card.fields.map { it.first })

        val quote = blocks.last() as ReportBlock.Quote
        assertEquals("这事包在我身上", quote.text)
        assertEquals("张三", quote.by)
    }

    @Test
    fun plainReplyStaysParagraphs() {
        val blocks = ReportParser.parse("你好。\n这是第二行。\n\n- 一\n- 二")
        assertEquals(listOf("Paragraph", "Bullets"), blocks.map { it::class.simpleName })
    }
}
