package com.iguar.armoredllama.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ReleaseParserTest {

    private val json = """
        {
          "tag_name": "b9999",
          "published_at": "2026-06-20T11:22:33Z",
          "body": "- faster prompt processing\n- fix mlock",
          "assets": [
            {"name": "llama-b9999-bin-ubuntu-x64.zip", "browser_download_url": "https://x/ubuntu"},
            {"name": "llama-b9999-bin-android-arm64.tar.gz", "browser_download_url": "https://x/android"}
          ]
        }
    """.trimIndent()

    @Test fun parseLatestRelease_extractsTagDateNotesAndArm64Asset() {
        val r = parseLatestRelease(json)!!
        assertEquals("b9999", r.tag)
        assertEquals("2026-06-20", r.date)
        assertTrue(r.notes.contains("faster prompt processing"))
        assertEquals("https://x/android", r.arm64AssetUrl)
    }

    @Test fun parseLatestRelease_arm64AssetNullWhenAbsent() {
        val noAsset = """{"tag_name":"b9999","published_at":"2026-06-20T00:00:00Z","body":"x","assets":[]}"""
        assertNull(parseLatestRelease(noAsset)!!.arm64AssetUrl)
    }

    @Test fun parseLatestRelease_returnsNullForGarbage() {
        assertNull(parseLatestRelease("not json"))
    }

    @Test fun androidArm64AssetName_formatsTag() {
        assertEquals("llama-b9999-bin-android-arm64.tar.gz", androidArm64AssetName("b9999"))
    }

    @Test fun parseBuildNumber_stripsBPrefix() {
        assertEquals(9775, parseBuildNumber("b9775"))
        assertNull(parseBuildNumber("nightly"))
    }

    @Test fun isNewer_comparesBuildNumbers() {
        assertTrue(isNewer("b9999", "b9775"))
        assertFalse(isNewer("b9775", "b9775"))
        assertFalse(isNewer("b9000", "b9775"))
    }
}
