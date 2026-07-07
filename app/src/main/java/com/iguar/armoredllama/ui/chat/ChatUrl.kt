package com.iguar.armoredllama.ui.chat

/** The llama-server web UI address for a configured port — Chat always targets loopback. */
fun chatUrl(port: Int): String = "http://127.0.0.1:$port/"
