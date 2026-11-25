package com.zeroui.template.feature.ai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeroui.kit.components.BreathingDot
import com.zeroui.kit.components.Icon
import com.zeroui.kit.theme.ZeroTheme
import com.zeroui.template.R
import com.zeroui.template.feature.update.markdownInline
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Tone of a highlight card. */
internal enum class CalloutKind { Tip, Warning, Note }

@Immutable
internal data class Callout(val kind: CalloutKind, val title: String, val body: String)

@Immutable
internal data class TimeEntry(val time: String, val text: String, val notes: List<ListChild>)

@Immutable
internal data class ListChild(val text: String, val quote: Boolean)

@Immutable
internal data class ListEntry(val number: String?, val text: String, val children: List<ListChild>)

/** Building block of an analysis reply. */
internal sealed interface ReportBlock {
    data class Heading(val text: String) : ReportBlock
    data class SubTitle(val text: String) : ReportBlock
    data class Paragraph(val text: String) : ReportBlock
    data class Callouts(val items: List<Callout>) : ReportBlock
    data class Quote(val text: String, val by: String?) : ReportBlock
    data class Timeline(val items: List<TimeEntry>) : ReportBlock
    data class Bullets(val items: List<ListEntry>) : ReportBlock
    data class ItemCard(val title: String, val fields: List<Pair<String, String>>, val notes: List<ListChild>) : ReportBlock
    data class Code(val text: String) : ReportBlock
}

/** Turns the markdown of a reply into report blocks. */
internal object ReportParser {
    private val heading = Regex("^\\s{0,3}(#{1,6})\\s+(.*)$")
    private val listItem = Regex("^([ \\t]*)([-*+•]|\\d{1,2}[.)、])\\s+(.*)$")
    private val quote = Regex("^\\s{0,3}>\\s?(.*)$")
    private val callout = Regex("^\\[!(\\w+)]\\s*(.*)$")
    private val rule = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val boldLine = Regex("^\\*\\*([^*]+)\\*\\*\\s*[:：]?\\s*$")
    private val timeLead = Regex("^\\*\\*([^*]{2,32}?)\\*\\*\\s*[:：·—-]*\\s*(.*)$")
    private val field = Regex("^\\*{0,2}([^：:*]{1,8}?)\\*{0,2}\\s*[:：]\\s*(.+)$")
    private val byline = Regex("^(.*?)\\s*(?:——|—|--)\\s*([^—\\-\\n]{1,24})$")

    fun parse(markdown: String): List<ReportBlock> {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<ReportBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.isBlank() || rule.matches(line) -> i++

                line.trimStart().startsWith("```") -> {
                    val body = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) body.append(lines[i++]).append('\n')
                    i++
                    out += ReportBlock.Code(body.toString().trimEnd())
                }

                heading.matches(line) -> {
                    val m = heading.find(line)!!
                    val text = m.groupValues[2].replace("**", "").trim()
                    out += if (m.groupValues[1].length <= 3) ReportBlock.Heading(text) else ReportBlock.SubTitle(text)
                    i++
                }

                quote.matches(line) -> {
                    val body = ArrayList<String>()
                    while (i < lines.size && quote.matches(lines[i])) body += quote.find(lines[i++])!!.groupValues[1]
                    addQuote(out, body)
                }

                listItem.matches(line) -> {
                    val block = ArrayList<String>()
                    while (i < lines.size && lines[i].isNotBlank() &&
                        (listItem.matches(lines[i]) || lines[i].first().isWhitespace())
                    ) block += lines[i++]
                    addList(out, block)
                }

                boldLine.matches(line.trim()) -> {
                    out += ReportBlock.SubTitle(boldLine.find(line.trim())!!.groupValues[1].trim())
                    i++
                }

                else -> {
                    val para = ArrayList<String>()
                    while (i < lines.size && lines[i].isNotBlank() && !startsBlock(lines[i]) && !boldLine.matches(lines[i].trim())) {
                        para += lines[i++].trim()
                    }
                    out += ReportBlock.Paragraph(para.joinToString("\n"))
                }
            }
        }
        return out
    }

    private fun startsBlock(line: String): Boolean =
        heading.matches(line) || quote.matches(line) || listItem.matches(line) || rule.matches(line) || line.trimStart().startsWith("```")

    private fun addQuote(out: MutableList<ReportBlock>, body: List<String>) {
        val first = body.firstOrNull { it.isNotBlank() } ?: return
        if (callout.matches(first.trim())) {
            val items = ArrayList<Callout>()
            var kind = CalloutKind.Note
            var title = ""
            val text = StringBuilder()
            fun flush() {
                if (title.isBlank() && text.isBlank()) return
                val t = title.ifBlank { text.toString().trim() }
                items += Callout(kind, t.replace("**", ""), if (title.isBlank()) "" else text.toString().trim())
            }
            for (raw in body) {
                val m = callout.find(raw.trim())
                if (m != null) {
                    flush()
                    kind = kindOf(m.groupValues[1])
                    title = m.groupValues[2].trim()
                    text.clear()
                } else if (raw.isNotBlank()) {
                    if (text.isNotEmpty()) text.append('\n')
                    text.append(raw.trim())
                }
            }
            flush()
            val last = out.lastOrNull()
            if (last is ReportBlock.Callouts) out[out.lastIndex] = ReportBlock.Callouts(last.items + items) else out += ReportBlock.Callouts(items)
            return
        }
        val lines = body.map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        var by: String? = null
        val tail = lines.last()
        if (lines.size > 1 && (tail.startsWith("——") || tail.startsWith("—") || tail.startsWith("--"))) {
            by = tail.trimStart('—', '-', ' ').trim()
            lines.removeAt(lines.lastIndex)
        } else {
            byline.find(tail)?.takeIf { it.groupValues[1].isNotBlank() }?.let {
                by = it.groupValues[2].trim()
                lines[lines.lastIndex] = it.groupValues[1].trim()
            }
        }
        out += ReportBlock.Quote(lines.joinToString("\n"), by?.ifBlank { null })
    }

    private fun kindOf(tag: String): CalloutKind = when (tag.uppercase()) {
        "TIP", "SUCCESS", "CHECK", "DONE" -> CalloutKind.Tip
        "WARNING", "CAUTION", "IMPORTANT", "DANGER" -> CalloutKind.Warning
        else -> CalloutKind.Note
    }

    private fun indentOf(s: String): Int = s.sumOf { if (it == '\t') 4L else 1L }.toInt()

    private fun addList(out: MutableList<ReportBlock>, block: List<String>) {
        class Raw(val number: String?, val text: String, val children: MutableList<ListChild>)
        val items = ArrayList<Raw>()
        val base = listItem.find(block.first())?.groupValues?.get(1)?.let(::indentOf) ?: 0
        for (line in block) {
            val m = listItem.find(line)
            val indent = m?.groupValues?.get(1)?.let(::indentOf) ?: Int.MAX_VALUE
            if (m != null && indent <= base + 1) {
                val marker = m.groupValues[2]
                items += Raw(marker.takeIf { it.first().isDigit() }?.trimEnd('.', ')', '、'), m.groupValues[3].trim(), ArrayList())
                continue
            }
            val owner = items.lastOrNull() ?: continue
            val q = quote.find(line.trim())
            when {
                q != null -> owner.children += ListChild(q.groupValues[1].trim(), quote = true)
                m != null -> owner.children += ListChild(m.groupValues[3].trim(), quote = false)
                else -> {
                    val prev = owner.children.lastOrNull()
                    if (prev == null) owner.children += ListChild(line.trim(), quote = false)
                    else owner.children[owner.children.lastIndex] = prev.copy(text = prev.text + "\n" + line.trim())
                }
            }
        }

        val times = ArrayList<TimeEntry>()
        val bullets = ArrayList<ListEntry>()
        fun flushTimes() {
            if (times.isEmpty()) return
            val last = out.lastOrNull()
            if (last is ReportBlock.Timeline) out[out.lastIndex] = ReportBlock.Timeline(last.items + times) else out += ReportBlock.Timeline(times.toList())
            times.clear()
        }
        fun flushBullets() {
            if (bullets.isEmpty()) return
            val last = out.lastOrNull()
            if (last is ReportBlock.Bullets) out[out.lastIndex] = ReportBlock.Bullets(last.items + bullets) else out += ReportBlock.Bullets(bullets.toList())
            bullets.clear()
        }
        for (item in items) {
            val t = timeLead.find(item.text)?.takeIf { looksLikeTime(it.groupValues[1]) }
            val fields = item.children.filter { !it.quote }.mapNotNull { c -> field.find(c.text)?.let { it.groupValues[1].trim() to it.groupValues[2].trim() } }
            when {
                t != null -> {
                    flushBullets()
                    times += TimeEntry(t.groupValues[1].trim(), t.groupValues[2].trim(), item.children)
                }
                fields.size >= 2 && fields.size * 2 >= item.children.size -> {
                    flushTimes()
                    flushBullets()
                    val notes = item.children.filter { c -> c.quote || field.find(c.text) == null }
                    out += ReportBlock.ItemCard(item.text, fields, notes)
                }
                else -> {
                    flushTimes()
                    bullets += ListEntry(item.number, item.text, item.children)
                }
            }
        }
        flushTimes()
        flushBullets()
    }

    private fun looksLikeTime(s: String): Boolean =
        s.length <= 24 && s.any { it.isDigit() } && s.any { it in "-/:：年月日点周" }
}

private val Danger: Color @Composable get() = ZeroTheme.colors.danger
private val Success = Color(0xFF2FA56A)

private const val CURSOR_ID = "report_cursor"

/** Styled view of an analysis reply: overview, highlight cards, sections, timelines, item cards and quotes. */
@Composable
internal fun AiReport(markdown: String, typing: Boolean, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { ReportParser.parse(markdown) }
    val structured = blocks.any { it is ReportBlock.Heading }
    val firstHeading = blocks.indexOfFirst { it is ReportBlock.Heading }
    Column(modifier) {
        if (structured) {
            Text(
                text = stringResource(R.string.ai_report_disclaimer),
                size = 12.sp,
                color = muted().copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        blocks.forEachIndexed { index, block ->
            val prev = blocks.getOrNull(index - 1)
            val cursor = typing && index == blocks.lastIndex
            val intro = structured && index < firstHeading
            val gap = when {
                index == 0 -> 0.dp
                prev is ReportBlock.Heading -> 0.dp
                block is ReportBlock.SubTitle -> 16.dp
                else -> 12.dp
            }
            Box(Modifier.padding(top = gap)) {
                when (block) {
                    is ReportBlock.Heading -> SectionHeading(block.text, divider = index > 0, cursor = cursor)
                    is ReportBlock.SubTitle -> Rich(block.text, size = 16.sp, line = 24.sp, color = strong(), weight = FontWeight.SemiBold, cursor = cursor)
                    is ReportBlock.Paragraph -> Rich(
                        block.text,
                        size = if (intro) 16.5.sp else 16.sp,
                        line = if (intro) 27.sp else 26.sp,
                        color = if (intro) muted() else body(),
                        cursor = cursor,
                    )
                    is ReportBlock.Callouts -> CalloutGrid(block.items)
                    is ReportBlock.Quote -> QuoteCard(block)
                    is ReportBlock.Timeline -> TimelineView(block.items)
                    is ReportBlock.Bullets -> BulletList(block.items)
                    is ReportBlock.ItemCard -> ItemCardView(block)
                    is ReportBlock.Code -> CodeView(block.text)
                }
            }
        }
        val inlineCursor = blocks.lastOrNull().let { it is ReportBlock.Paragraph || it is ReportBlock.SubTitle || it is ReportBlock.Heading }
        if (typing && !inlineCursor) {
            BreathingDot(modifier = Modifier.padding(top = 12.dp).size(9.dp), color = muted())
        }
    }
}

@Composable private fun strong(): Color = MiuixTheme.colorScheme.onSurface
@Composable private fun body(): Color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.86f)
@Composable private fun muted(): Color = MiuixTheme.colorScheme.onSurfaceVariantSummary
@Composable private fun accent(): Color = MiuixTheme.colorScheme.primary

@Composable
private fun styled(text: String, color: Color): AnnotatedString {
    val strong = strong()
    val codeBg = MiuixTheme.colorScheme.surfaceContainer
    val link = accent()
    return remember(text, color, strong) { markdownInline(text, color, strong, codeBg, link) }
}

/** Plain text with the report's base style. */
@Composable
private fun Text(
    text: String,
    size: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight? = null,
    align: TextAlign? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size, lineHeight = size * 1.45f, fontWeight = weight, textAlign = align ?: TextAlign.Unspecified),
    )
}

/** Markdown-styled text with an optional breathing cursor at its end. */
@Composable
private fun Rich(
    text: String,
    size: TextUnit,
    line: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight? = null,
    cursor: Boolean = false,
) {
    val content = styled(text, color)
    val dotColor = muted()
    val dot = (size.value * 0.55f).sp
    val inline = remember(size, dotColor) {
        mapOf(CURSOR_ID to InlineTextContent(Placeholder(dot, dot, PlaceholderVerticalAlign.Center)) { BreathingDot(Modifier.fillMaxSize(), color = dotColor) })
    }
    BasicText(
        text = if (!cursor) content else buildAnnotatedString {
            append(content)
            append(' ')
            appendInlineContent(CURSOR_ID, "●")
        },
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size, lineHeight = line, fontWeight = weight),
        inlineContent = if (cursor) inline else emptyMap(),
    )
}

/** Section title, separated from the previous section by a hairline. */
@Composable
private fun SectionHeading(text: String, divider: Boolean, cursor: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        if (divider) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(0.6.dp).background(MiuixTheme.colorScheme.dividerLine))
            Spacer(Modifier.height(22.dp))
        }
        Rich(text, size = 20.sp, line = 28.sp, color = strong(), weight = FontWeight.Bold, cursor = cursor)
        Spacer(Modifier.height(12.dp))
    }
}

/** Highlight cards; short neighbours share a row. */
@Composable
private fun CalloutGrid(items: List<Callout>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        var i = 0
        while (i < items.size) {
            val a = items[i]
            val b = items.getOrNull(i + 1)
            if (b != null && a.isShort() && b.isShort()) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CalloutCard(a, Modifier.weight(1f).fillMaxHeight())
                    CalloutCard(b, Modifier.weight(1f).fillMaxHeight())
                }
                i += 2
            } else {
                CalloutCard(a, Modifier.fillMaxWidth())
                i += 1
            }
        }
    }
}

private fun Callout.isShort(): Boolean = title.length <= 10 && body.length <= 40

@Composable
private fun CalloutCard(item: Callout, modifier: Modifier) {
    val tone = when (item.kind) {
        CalloutKind.Tip -> Success
        CalloutKind.Warning -> Danger
        CalloutKind.Note -> accent()
    }
    val icon = when (item.kind) {
        CalloutKind.Tip -> R.drawable.wd_ic_check
        CalloutKind.Warning -> R.drawable.wd_ic_warn
        CalloutKind.Note -> R.drawable.wd_ic_info
    }
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(tone.copy(alpha = 0.08f))
            .border(1.dp, tone.copy(alpha = 0.22f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = ImageVector.vectorResource(icon),
                contentDescription = null,
                tint = tone,
                modifier = Modifier.padding(top = 2.dp, end = 6.dp).size(18.dp),
            )
            Rich(item.title, size = 15.5.sp, line = 22.sp, color = tone, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        if (item.body.isNotBlank()) {
            Rich(item.body, size = 14.5.sp, line = 22.sp, color = body(), modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Rounded capsule with an outline, used for times. */
@Composable
private fun Pill(text: String) {
    val tone = accent()
    Text(
        text = text,
        size = 12.5.sp,
        color = tone,
        weight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tone.copy(alpha = 0.06f))
            .border(1.dp, tone.copy(alpha = 0.3f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 1.dp),
    )
}

/** Dated entries joined by a vertical line. */
@Composable
private fun TimelineView(items: List<TimeEntry>) {
    val line = MiuixTheme.colorScheme.dividerLine
    val dot = accent()
    Column(Modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            val last = index == items.lastIndex
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Column(Modifier.width(18.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.padding(top = 7.dp).size(7.dp).clip(CircleShape).background(dot))
                    if (!last) Box(Modifier.padding(top = 4.dp).width(1.dp).weight(1f).background(line))
                }
                Column(Modifier.weight(1f).padding(start = 8.dp, bottom = if (last) 0.dp else 16.dp)) {
                    Pill(item.time)
                    if (item.text.isNotBlank()) {
                        Rich(item.text, size = 15.5.sp, line = 24.sp, color = body(), modifier = Modifier.padding(top = 6.dp))
                    }
                    item.notes.forEach { Child(it) }
                }
            }
        }
    }
}

@Composable
private fun BulletList(items: List<ListEntry>) {
    val tone = accent()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            Row(Modifier.fillMaxWidth()) {
                if (item.number != null) {
                    Text(item.number, size = 15.sp, color = tone, weight = FontWeight.Bold, modifier = Modifier.width(22.dp).padding(top = 2.dp))
                } else {
                    Box(Modifier.width(22.dp).padding(top = 10.dp)) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(tone))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Rich(item.text, size = 16.sp, line = 26.sp, color = body())
                    item.children.forEach { Child(it) }
                }
            }
        }
    }
}

/** Nested line under a list entry: a quote with a side bar, or a muted note. */
@Composable
private fun Child(child: ListChild) {
    if (child.quote) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp).height(IntrinsicSize.Min)) {
            Box(Modifier.width(2.dp).fillMaxHeight().clip(RoundedCornerShape(1.dp)).background(MiuixTheme.colorScheme.dividerLine))
            Rich(child.text, size = 14.5.sp, line = 22.sp, color = muted(), modifier = Modifier.padding(start = 10.dp))
        }
    } else {
        Rich(child.text, size = 14.5.sp, line = 22.sp, color = muted(), modifier = Modifier.padding(top = 4.dp))
    }
}

/** Card for an entry with labelled properties. */
@Composable
private fun ItemCardView(card: ReportBlock.ItemCard) {
    val labelWidth: Dp = (card.fields.maxOf { it.first.length } * 15 + 14).coerceIn(48, 100).dp
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Rich(card.title, size = 16.sp, line = 24.sp, color = strong(), weight = FontWeight.SemiBold)
        DashedLine(Modifier.padding(vertical = 12.dp))
        card.fields.forEachIndexed { index, (label, value) ->
            Row(Modifier.fillMaxWidth().padding(top = if (index == 0) 0.dp else 6.dp)) {
                Text(label, size = 14.5.sp, color = muted(), modifier = Modifier.width(labelWidth))
                Rich(value, size = 14.5.sp, line = 21.sp, color = statusTone(value) ?: body(), modifier = Modifier.weight(1f))
            }
        }
        card.notes.forEach { Child(it) }
    }
}

@Composable
private fun statusTone(value: String): Color? = when {
    value.startsWith("未完成") || value.startsWith("逾期") || value.startsWith("待") -> Danger
    value.startsWith("已完成") -> Success
    else -> null
}

@Composable
private fun DashedLine(modifier: Modifier = Modifier) {
    val color = MiuixTheme.colorScheme.dividerLine
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = size.height,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
        )
    }
}

/** Quoted words with an opening mark and the speaker at the bottom right. */
@Composable
private fun QuoteCard(quote: ReportBlock.Quote) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("“", size = 30.sp, color = accent().copy(alpha = 0.55f), weight = FontWeight.Bold, modifier = Modifier.height(30.dp))
        Rich(quote.text, size = 16.sp, line = 26.sp, color = strong())
        if (quote.by != null) {
            Text(
                "—— ${quote.by}",
                size = 13.5.sp,
                color = muted(),
                align = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun CodeView(code: String) {
    Text(
        code,
        size = 13.sp,
        color = strong(),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}
