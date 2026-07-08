package com.iguar.armoredllama.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatUrlTest {
    @Test fun chatUrl_buildsLoopbackUrlFromPort() {
        assertEquals("http://127.0.0.1:8080/", chatUrl(8080))
    }

    @Test fun chatUrl_usesGivenPortVerbatim() {
        assertEquals("http://127.0.0.1:9001/", chatUrl(9001))
    }
}
