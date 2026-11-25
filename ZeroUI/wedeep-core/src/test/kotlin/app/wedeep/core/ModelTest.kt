package app.wedeep.core

import app.wedeep.core.crypto.md5Hex
import app.wedeep.core.media.MediaPaths
import app.wedeep.core.model.Kind
import app.wedeep.core.model.MessageParser
import app.wedeep.core.model.Xml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    @Test fun md5() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5Hex(""))
    }

    @Test fun parsesMessages() {
        assertEquals(Kind.Text, MessageParser.parse(1, "hi").kind)
        assertEquals(3, MessageParser.parse(34, """<msg><voicemsg voicelength="2600" /></msg>""").seconds)
        val link = MessageParser.parse(49, "<msg><appmsg><title>T</title><des>D</des><type>5</type><url>http://a</url></appmsg></msg>")
        assertEquals(Kind.Link, link.kind)
        assertEquals("http://a", link.url)
        assertEquals(Kind.Link, MessageParser.parse(268435505, "<msg><appmsg><title>T</title><type>5</type></appmsg></msg>").kind)
        assertEquals(Kind.Unknown, MessageParser.parse(99999, "x").kind)
        val split = MessageParser.splitSender("wxid_abc:\nhello")
        assertEquals("wxid_abc", split.sender)
        assertEquals("hello", split.body)
        assertEquals(null, MessageParser.splitSender("plain: text").sender)
    }

    @Test fun decodesNumericEntities() {
        assertEquals("a b" + Char(10) + "c&", Xml.unescape("a&#x20;b&#x0A;c&amp;"))
        assertEquals("A", Xml.unescape("&#65;"))
    }

    @Test fun mediaPaths() {
        assertEquals("image2/3a/f1/th_3af1c0", MediaPaths.image("THUMBNAIL_DIRPATH://th_3af1c0").first())
        assertTrue(MediaPaths.voice("abc").first().endsWith("/msg_abc.amr"))
        assertTrue(MediaPaths.isSilk("\u0002#!SILK_V3".toByteArray(Charsets.ISO_8859_1)))
    }
}
