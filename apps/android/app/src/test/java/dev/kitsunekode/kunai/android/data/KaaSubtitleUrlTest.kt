package dev.kitsunekode.kunai.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class KaaSubtitleUrlTest {
    @Test fun tripleSlashSubtitlesBecomePublicHttps() {
        assertEquals("https://subbl.example.com/en.srt",
            normalizeProviderScheme("https:///subbl.example.com/en.srt"))
    }

    @Test fun quadSlashSubtitlesBecomePublicHttps() {
        assertEquals("https://subbl.example.com/en.srt",
            normalizeProviderScheme("https:////subbl.example.com/en.srt"))
    }

    @Test fun protocolRelativeVideoPreserved() {
        assertEquals("https://cdn.example.com/master.m3u8",
            normalizeProviderScheme("//cdn.example.com/master.m3u8"))
    }

    @Test fun formatIsSniffedFromBytesNotFilename() {
        assertEquals("text/vtt", subtitleMimeFromHeader(
            "WEBVTT\n\n00:00:04.500 --> 00:00:06.680".toByteArray()))
        assertEquals("text/vtt", subtitleMimeFromHeader(
            "\uFEFFWEBVTT\r\n\r\n".toByteArray()))
        assertEquals("application/x-subrip", subtitleMimeFromHeader(
            "1\r\n00:00:04,500 --> 00:00:06,680\r\nHello".toByteArray()))
        assertEquals(null, subtitleMimeFromHeader("<html>error</html>".toByteArray()))
    }

    @Test fun standardHttpsAndRejectedHttpAreNotModified() {
        assertEquals("https://cdn.example.com/en.vtt",
            normalizeProviderScheme("https://cdn.example.com/en.vtt"))
        assertEquals("http://local.test/unsafe.srt",
            normalizeProviderScheme("http://local.test/unsafe.srt"))
    }
}
