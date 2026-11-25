package app.wedeep.core.model

/** Kinds of message content the app can render. */
enum class Kind { Text, Image, Voice, Video, Emoji, Location, Card, Link, File, Quote, MiniProgram, Forward, Transfer, RedPacket, Call, System, Unknown }

/** Rendered form of a message body. */
data class Content(
    val kind: Kind,
    val text: String,
    val title: String? = null,
    val detail: String? = null,
    val url: String? = null,
    val seconds: Int = 0,
    val raw: String? = null,
)

/** Splits a group message body into the sender id and the text. */
data class SenderSplit(val sender: String?, val body: String)

object MessageParser {
    private val senderPrefix = Regex("""^([A-Za-z0-9_\-@.]{2,64}):\r?\n""")

    /** Low 16 bits of the stored type identify the family; the high bits are flags. */
    fun family(type: Int): Int = type and 0xFFFF

    fun splitSender(content: String): SenderSplit {
        val m = senderPrefix.find(content) ?: return SenderSplit(null, content)
        return SenderSplit(m.groupValues[1], content.substring(m.range.last + 1))
    }

    fun parse(type: Int, body: String): Content {
        return when (family(type)) {
            1 -> Content(Kind.Text, body)
            3 -> Content(Kind.Image, "[图片]")
            34 -> {
                val ms = Xml.attr(body, "voicemsg", "voicelength")?.toIntOrNull() ?: 0
                Content(Kind.Voice, "[语音]", seconds = (ms + 500) / 1000)
            }
            43, 62 -> Content(Kind.Video, "[视频]", seconds = Xml.attr(body, "videomsg", "playlength")?.toIntOrNull() ?: 0)
            47 -> Content(Kind.Emoji, "[表情]")
            48 -> {
                val label = Xml.attr(body, "location", "poiname").orEmpty().ifBlank { Xml.attr(body, "location", "label").orEmpty() }
                val x = Xml.attr(body, "location", "x").orEmpty()
                val y = Xml.attr(body, "location", "y").orEmpty()
                Content(Kind.Location, "[位置] $label".trim(), title = label, detail = "$x, $y")
            }
            42 -> {
                val nick = Xml.attr(body, "msg", "nickname").orEmpty()
                Content(Kind.Card, "[名片] $nick".trim(), title = nick, detail = Xml.attr(body, "msg", "username"))
            }
            50 -> Content(Kind.Call, "[通话]")
            10000, 10002 -> Content(Kind.System, Xml.stripTags(body).trim())
            49 -> parseApp(body)
            else -> Content(Kind.Unknown, "[暂不支持的消息]", raw = body)
        }
    }

    private fun parseApp(body: String): Content {
        val sub = Xml.text(body, "type")?.toIntOrNull()
        val title = Xml.text(body, "title").orEmpty()
        val des = Xml.text(body, "des")
        val url = Xml.text(body, "url")
        return when (sub) {
            3, 4, 5 -> Content(Kind.Link, "[链接] $title".trim(), title = title, detail = des, url = url)
            6, 74 -> Content(Kind.File, "[文件] $title".trim(), title = title, detail = Xml.text(body, "totallen"))
            19 -> Content(Kind.Forward, "[聊天记录] $title".trim(), title = title, detail = des)
            33, 36 -> Content(Kind.MiniProgram, "[小程序] $title".trim(), title = title, detail = des, url = url)
            57 -> Content(Kind.Quote, title, title = title, detail = Xml.text(body, "displayname")?.let { "$it: ${Xml.text(body, "content").orEmpty()}" })
            2000 -> Content(Kind.Transfer, "[转账] $title".trim(), title = title, detail = des)
            2001 -> Content(Kind.RedPacket, "[红包] $title".trim(), title = title, detail = des)
            else -> Content(Kind.Unknown, if (title.isBlank()) "[暂不支持的卡片]" else "[卡片] $title", title = title, detail = des, url = url, raw = body)
        }
    }

    /** One-line summary for conversation lists. */
    fun summary(type: Int, body: String): String {
        val text = splitSender(body).body
        val c = parse(type, text)
        return if (c.kind == Kind.System) c.text else c.text.replace('\n', ' ')
    }
}

/** Minimal tolerant XML reading for message bodies. */
object Xml {
    private val cdata = Regex("""^\s*<!\[CDATA\[(.*?)]]>\s*$""", RegexOption.DOT_MATCHES_ALL)
    private val tag = Regex("""<[^>]*>""")

    /** Text of the first `<name>…</name>` element. */
    fun text(xml: String, name: String): String? {
        val m = Regex("<$name(?:\\s[^>]*)?>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml) ?: return null
        val inner = m.groupValues[1]
        return unescape(cdata.find(inner)?.groupValues?.get(1) ?: inner).trim()
    }

    /** Value of attribute [attr] on the first `<name …>` element. */
    fun attr(xml: String, name: String, attr: String): String? {
        val open = Regex("<$name(\\s[^>]*)?>").find(xml) ?: return null
        val a = Regex("""\b$attr\s*=\s*"([^"]*)"""").find(open.groupValues[1]) ?: return null
        return unescape(a.groupValues[1])
    }

    fun stripTags(xml: String): String = unescape(tag.replace(xml, ""))

    private val numericRef = Regex("&#(x[0-9A-Fa-f]{1,6}|[0-9]{1,7});")

    fun unescape(s: String): String {
        val decoded = numericRef.replace(s) { m ->
            val body = m.groupValues[1]
            val code = if (body.startsWith("x")) body.substring(1).toIntOrNull(16) else body.toIntOrNull()
            if (code == null || code > 0x10FFFF) m.value else String(Character.toChars(code))
        }
        return decoded.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }
}
