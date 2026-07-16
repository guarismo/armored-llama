package com.iguar.armoredllama.server

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseParserSanitizeTest {

    @Test fun extractsContentBetweenDetailsTags() {
        val raw = "Preamble\n<details><summary>Log</summary>\nchanges here\n</details>\nDownload links"
        val result = sanitizeNotes(raw)
        assertEquals("Log\nchanges here", result)
    }

    @Test fun discardsContentOutsideDetails() {
        val raw = "Before\n<details>\ninside\n</details>\nAfter stuff"
        val result = sanitizeNotes(raw)
        assertEquals("inside", result)
    }

    @Test fun multipleDetailsBlocksConcatenatedWithBlankLine() {
        val raw = "<details>\nfirst block\n</details>\nmiddle\n<details>\nsecond block\n</details>\ntrailing"
        val result = sanitizeNotes(raw)
        assertEquals("first block\n\nsecond block", result)
    }

    @Test fun fallbackStripsFullBodyWhenNoDetailsBlock() {
        val raw = "## Changelog\n**bold** and [link](http://example.com)"
        val result = sanitizeNotes(raw)
        assertEquals("Changelog\nbold and link", result)
    }

    @Test fun stripsInnerHtmlTags() {
        val raw = "<details><summary>Log</summary><br>line one<br/>line two</details>"
        val result = sanitizeNotes(raw)
        assertEquals("Logline oneline two", result)
    }

    @Test fun unwrapsBoldMarkdown() {
        val raw = "<details>\n**important** text\n</details>"
        val result = sanitizeNotes(raw)
        assertEquals("important text", result)
    }

    @Test fun convertsMarkdownLinksToText() {
        val raw = "<details>\nSee [docs](https://example.com/docs) for info\n</details>"
        val result = sanitizeNotes(raw)
        assertEquals("See docs for info", result)
    }

    @Test fun removesHeadingMarkers() {
        val raw = "<details>\n## Heading\nBody\n</details>"
        val result = sanitizeNotes(raw)
        assertEquals("Heading\nBody", result)
    }

    @Test fun convertsBulletMarkers() {
        val raw = "<details>\n* item one\n* item two\n</details>"
        val result = sanitizeNotes(raw)
        assertEquals("- item one\n- item two", result)
    }

    @Test fun collapsesExcessiveNewlines() {
        val raw = "<details>\nfirst\n\n\n\nsecond\n</details>"
        val result = sanitizeNotes(raw)
        assertEquals("first\n\nsecond", result)
    }

    @Test fun emptyInputReturnsEmptyString() {
        assertEquals("", sanitizeNotes(""))
    }

    @Test fun blankInputReturnsEmptyString() {
        assertEquals("", sanitizeNotes("   \n  \n  "))
    }
}
